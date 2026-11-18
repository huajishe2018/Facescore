# 面部分析 Android 课程设计

> 公开版本：不包含课程报告、测试证据、个人截图和真实服务器地址；默认后端地址为占位符 `https://example.com/facescore`。

Java 17 和 XML 布局。启动先登录或注册，再进入颜值打分或照片锐评。相机预览使用 CameraX，端侧人脸检测使用随 APK 打包的 ML Kit 模型。

## 在 Android Studio 中运行

1. 用 Android Studio 打开此目录。选择 JDK 17 作为 Gradle JDK，并在 SDK Manager 安装 Android SDK Platform 35 和 Build Tools 35.0.0。
2. 在 Device Manager 创建 Android 15（API 35）模拟器，例如 Pixel 设备。测试自拍时可在虚拟设备设置里将前置相机设为本机摄像头。相册流程可用拖入模拟器的测试图片验证。
3. 选择 `app` 和创建的模拟器，点击 Run。也可以在 PowerShell 用 `.\gradlew.bat :app:assembleDebug` 生成调试 APK。

## 操作流程

- 登录成功或注册成功后，选择「颜值打分 · 本机」或「照片锐评 · 云端」。
- 打分模块可选单人照查看评分和测量，也可打开实时打分。实时打分以 500 毫秒为目标采样间隔，推理繁忙时取最新可用帧。
- 锐评模块可选照片或打开相机拍照。取得照片后由服务器转交百炼生成文字评价。

## 接入端侧评分

`LocalFaceScorer.java` 通过 LiteRT 加载 `app/src/main/assets/face_score.tflite`。输入是转正后的 128×128 RGB 人脸裁剪图，归一化在模型中完成。模型缺失时显示明确标记的示例分数。面部测量单独展示。

用 Python 3.12 安装 `training/requirements.txt`，下载并解压 [SCUT-FBP5500](https://github.com/HCIILAB/SCUT-FBP5500-Database-Release)，从项目根目录运行：

```powershell
python -m pip install -r training/requirements.txt
python training/train.py "C:\path\to\SCUT-FBP5500_v2"
New-Item -ItemType Directory -Force app/src/main/assets
Copy-Item training/output/face_score.tflite app/src/main/assets/
```

训练使用 ImageNet 预训练的 MobileNetV2 提取特征，并训练 1–5 分的回归头。脚本按数据子集分层划分训练、验证和测试集，输出 MAE、RMSE、Pearson 相关系数和均值基线，并检查导出前后的单张图预测是否一致。训练用 OpenCV 检测裁剪，手机用 ML Kit。裁剪差异和真实拍摄条件可能使手机表现和离线指标不同。

## 云端照片锐评

App 通过 HTTPS 访问部署在云服务器上的注册、登录和锐评接口。服务器使用百炼的 `qwen3-vl-plus`，默认每个账号每天可请求 10 次锐评。用户在锐评模式选图或拍照后，压缩后的 JPEG 会发往服务器和百炼。

在服务器上将 `DASHSCOPE_API_KEY` 配置到仅 root 可读的 `/etc/facescore/api.env`，再运行 `sudo systemctl restart facescore-api`。访问 `/facescore/health` 可以检查服务和密钥配置状态，实际模型调用需要用照片验证。APK 不内置模型密钥。

更换服务器地址时，构建可传入 `-PfacescoreBackendUrl=https://新地址/facescore`。公开版本默认地址是 `https://example.com/facescore`，仅作占位，不代表可直接使用的服务。服务端源码在 `server/facescore_api.py`。
