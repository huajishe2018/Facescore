"""FaceScore API. Bind to loopback and expose through an HTTPS reverse proxy."""

import base64
from contextlib import closing
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import secrets
import sqlite3
import time
from urllib.error import URLError
from urllib.request import Request, urlopen

DATABASE = os.environ.get("FACESCORE_DB", "facescore.sqlite3")
SESSION_SECONDS = 7 * 86400
PROMPT = (
    "用简体中文点评这张单人照片，语气直接、具体，避免辱骂。"
    "分三段：上镜印象（直接说这张照片帅不帅并给出可见理由）、造型风格、两条具体提升建议。"
    "只谈画面里看得到的内容。多人或看不清，请换清晰单人照。限200字。"
)


def database():
    connection = sqlite3.connect(DATABASE, timeout=10)
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def initialize():
    with closing(database()) as db:
        db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS users (
                name TEXT PRIMARY KEY, salt BLOB NOT NULL, digest BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                digest TEXT PRIMARY KEY, name TEXT NOT NULL REFERENCES users(name),
                expires INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS review_usage (
                name TEXT NOT NULL REFERENCES users(name), day INTEGER NOT NULL,
                count INTEGER NOT NULL, PRIMARY KEY(name, day)
            );
            CREATE TABLE IF NOT EXISTS auth_limits (
                address TEXT NOT NULL, window INTEGER NOT NULL, count INTEGER NOT NULL,
                PRIMARY KEY(address, window)
            );
        """)


def password_digest(password, salt):
    return hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1)


def token_digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


class ApiError(Exception):
    def __init__(self, status, message):
        self.status = status
        self.message = message


def review_photo(photo, key, model):
    data_url = "data:image/jpeg;base64," + base64.b64encode(photo).decode()
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": [
            {"type": "image_url", "image_url": {"url": data_url}},
            {"type": "text", "text": PROMPT},
        ]}],
        "max_tokens": 500,
    }
    request = Request(
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
    )
    try:
        with urlopen(request, timeout=45) as response:
            result = json.loads(response.read(262145))
        review = result["choices"][0]["message"]["content"].strip()
        if not review:
            raise ValueError("empty review")
        return review
    except (URLError, OSError, ValueError, KeyError, IndexError, AttributeError, TypeError) as error:
        raise ApiError(502, "模型服务暂不可用") from error


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, body):
        encoded = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)
        self.close_connection = True

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "ok", "review_ready": bool(os.environ.get("DASHSCOPE_API_KEY"))})
        else:
            self.reply(404, {"error": "接口不存在"})

    def body(self, max_length):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise ApiError(400, "无效的请求长度") from error
        if not 0 < length <= max_length:
            raise ApiError(413, "请求为空或太大")
        self.connection.settimeout(15)
        data = self.rfile.read(length)
        if len(data) != length:
            raise ApiError(400, "请求未传输完整")
        return data

    def credentials(self, register):
        address = self.headers.get("X-Real-IP", self.client_address[0])
        window = int(time.time()) // 600
        with closing(database()) as db, db:
            db.execute("DELETE FROM auth_limits WHERE window < ?", (window - 1,))
            attempts, = db.execute("""
                INSERT INTO auth_limits VALUES (?, ?, 1)
                ON CONFLICT(address, window) DO UPDATE SET count = count + 1 RETURNING count
            """, (address, window)).fetchone()
        if attempts > 20:
            raise ApiError(429, "操作过于频繁，请稍后再试")
        payload = json.loads(self.body(4096))
        if not isinstance(payload, dict):
            raise ApiError(400, "需要用户名和密码")
        name, password = payload.get("username"), payload.get("password")
        if (not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9_]{3,32}", name)
                or not isinstance(password, str) or not 8 <= len(password) <= 128):
            raise ApiError(400, "用户名需为3–32位字母、数字或下划线，密码需为8–128个字符")
        name = name.lower()
        with closing(database()) as db:
            if register:
                salt = secrets.token_bytes(16)
                digest = password_digest(password, salt)
                try:
                    with db:
                        db.execute("INSERT INTO users VALUES (?, ?, ?)", (name, salt, digest))
                except sqlite3.IntegrityError as error:
                    raise ApiError(409, "用户名已存在") from error
            else:
                row = db.execute("SELECT salt, digest FROM users WHERE name = ?", (name,)).fetchone()
                salt, digest = row or (bytes(16), bytes(64))
                if not secrets.compare_digest(password_digest(password, salt), digest):
                    raise ApiError(401, "用户名或密码错误")
            token = secrets.token_urlsafe(32)
            expires = int(time.time()) + SESSION_SECONDS
            with db:
                db.execute("DELETE FROM sessions WHERE expires <= ?", (int(time.time()),))
                db.execute("INSERT INTO sessions VALUES (?, ?, ?)", (token_digest(token), name, expires))
        return {"token": token, "expires_at": expires}

    def review(self):
        scheme, _, token = self.headers.get("Authorization", "").partition(" ")
        if scheme.lower() != "bearer" or len(token) > 128 or not token:
            raise ApiError(401, "请先登录")
        now = int(time.time())
        with closing(database()) as db:
            user = db.execute("SELECT name FROM sessions WHERE digest = ? AND expires > ?",
                              (token_digest(token), now)).fetchone()
        if not user:
            raise ApiError(401, "登录已失效")
        key = os.environ.get("DASHSCOPE_API_KEY")
        if not key:
            raise ApiError(503, "服务器还未配置视觉模型")
        if self.headers.get_content_type() != "image/jpeg":
            raise ApiError(415, "请发送 JPEG 照片")
        photo = self.body(2 * 1024 * 1024)
        if not photo.startswith(b"\xff\xd8\xff"):
            raise ApiError(400, "照片格式无效")
        day = now // 86400
        with closing(database()) as db, db:
            used = db.execute("""
                INSERT INTO review_usage VALUES (?, ?, 1)
                ON CONFLICT(name, day) DO UPDATE SET count = count + 1
                WHERE count < 10 RETURNING count
            """, (user[0], day)).fetchone()
            if not used:
                raise ApiError(429, "今天的锐评次数已用完")
        try:
            review = review_photo(photo, key, os.environ.get("FACESCORE_MODEL", "qwen3-vl-plus"))
        except Exception as error:
            with closing(database()) as db, db:
                db.execute("""
                    UPDATE review_usage SET count = count - 1
                    WHERE name = ? AND day = ? AND count > 0
                """, (user[0], day))
                db.execute("DELETE FROM review_usage WHERE name = ? AND day = ? AND count <= 0",
                           (user[0], day))
            if isinstance(error, ApiError):
                raise
            raise ApiError(502, "模型服务暂不可用") from error
        return {"review": review}

    def do_POST(self):
        try:
            if self.path in ("/register", "/login"):
                self.reply(200, self.credentials(self.path == "/register"))
            elif self.path == "/review":
                self.reply(200, self.review())
            else:
                self.reply(404, {"error": "接口不存在"})
        except ApiError as error:
            self.reply(error.status, {"error": error.message})
        except (json.JSONDecodeError, UnicodeDecodeError):
            self.reply(400, {"error": "请求格式无效"})
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True


if __name__ == "__main__":
    initialize()
    ThreadingHTTPServer(("127.0.0.1", 18535), Handler).serve_forever()
