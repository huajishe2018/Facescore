package com.example.facescore;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.facescore.databinding.ActivityMainBinding;
import com.example.facescore.databinding.ScreenCameraBinding;
import com.example.facescore.databinding.ScreenAuthBinding;
import com.example.facescore.databinding.ScreenHomeBinding;
import com.example.facescore.databinding.ScreenResultBinding;
import com.example.facescore.databinding.ScreenStatusBinding;

public final class MainActivity extends ComponentActivity {
    private FaceScoreViewModel model;
    private ActivityMainBinding layout;
    private CameraSession camera;
    private OnBackPressedCallback goHome;

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) model.analyzePhoto(uri);
            });

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    model.openCamera();
                } else {
                    model.showError(R.string.camera_permission_denied);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        layout = ActivityMainBinding.inflate(getLayoutInflater());
        ViewCompat.setOnApplyWindowInsetsListener(layout.getRoot(), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(layout.getRoot());

        model = new ViewModelProvider(this).get(FaceScoreViewModel.class);
        goHome = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                navigateBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, goHome);
        layout.backHome.setOnClickListener(view -> navigateBack());
        model.state().observe(this, this::render);
    }

    private void navigateBack() {
        switch (model.state().getValue().page()) {
            case CAMERA, RESULT, REVIEW -> model.showInput();
            case ANALYZING, ERROR -> {
                if (model.hasSession()) model.showInput();
                else model.showAuth(model.isRegistering());
            }
            default -> model.showHome();
        }
    }

    private void render(FaceScoreViewModel.State state) {
        closeCamera();
        layout.screen.removeAllViews();
        boolean home = state.page() == FaceScoreViewModel.Page.HOME
                || state.page() == FaceScoreViewModel.Page.LOGIN;
        goHome.setEnabled(!home);
        layout.backHome.setVisibility(home ? View.GONE : View.VISIBLE);
        switch (state.page()) {
            case LOGIN, REGISTER -> showAuth(state.page() == FaceScoreViewModel.Page.REGISTER);
            case HOME, INPUT -> showHome(state.page() == FaceScoreViewModel.Page.HOME);
            case CAMERA -> showCamera();
            case ANALYZING, ERROR -> showStatus(state);
            case REVIEW -> showReview(state.text());
            case RESULT -> showResult(state.result());
        }
    }

    private void showHome(boolean modules) {
        ScreenHomeBinding screen = ScreenHomeBinding.inflate(getLayoutInflater(), layout.screen, true);
        screen.scoreMode.setVisibility(modules ? View.VISIBLE : View.GONE);
        screen.reviewMode.setVisibility(modules ? View.VISIBLE : View.GONE);
        screen.choosePhoto.setVisibility(modules ? View.GONE : View.VISIBLE);
        screen.openCamera.setVisibility(modules ? View.GONE : View.VISIBLE);
        screen.scoreMode.setOnClickListener(view -> model.selectMode(false));
        screen.reviewMode.setOnClickListener(view -> model.selectMode(true));
        screen.modeNote.setVisibility(modules ? View.GONE : View.VISIBLE);
        screen.modeNote.setText(model.isReview()
                ? "锐评会将选中的照片发送到服务器和视觉模型服务。"
                : "照片和实时打分在本机处理。");
        if (!modules) {
            screen.homeTitle.setText(model.isReview() ? "照片锐评" : "颜值打分");
            screen.homeIntro.setText("选择一种照片来源");
            screen.openCamera.setText(model.isReview() ? "打开相机拍照" : "实时打分");
        }
        screen.choosePhoto.setOnClickListener(view -> choosePhoto());
        screen.openCamera.setOnClickListener(view -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                model.openCamera();
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA);
            }
        });
    }

    private void showReview(String text) {
        ScreenStatusBinding screen = ScreenStatusBinding.inflate(getLayoutInflater(), layout.screen, true);
        screen.title.setText("照片锐评");
        screen.progress.setVisibility(View.GONE);
        screen.detail.setGravity(android.view.Gravity.START);
        screen.detail.setText(text);
        screen.choosePhoto.setText("再评一张");
        screen.choosePhoto.setOnClickListener(view -> choosePhoto());
    }

    private void showAuth(boolean register) {
        ScreenAuthBinding screen = ScreenAuthBinding.inflate(getLayoutInflater(), layout.screen, true);
        screen.authTitle.setText(register ? "注册账号" : "登录");
        screen.authSubmit.setText(register ? "注册并登录" : "登录");
        screen.authSwitch.setText(register ? "已有账号？去登录" : "没有账号？去注册");
        screen.authSwitch.setOnClickListener(view -> model.showAuth(!register));
        screen.authSubmit.setOnClickListener(view -> {
            String username = screen.username.getText().toString().trim();
            String password = screen.password.getText().toString();
            if (username.isEmpty()) {
                screen.username.setError("请输入用户名");
            } else if (password.isEmpty()) {
                screen.password.setError("请输入密码");
            } else {
                model.authenticate(username, password, register);
            }
        });
    }

    private void choosePhoto() {
        if (!model.hasSession()) {
            model.showAuth(false);
            return;
        }
        photoPicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void showCamera() {
        ScreenCameraBinding screen = ScreenCameraBinding.inflate(getLayoutInflater(), layout.screen, true);
        CameraSession session = new CameraSession(this);
        camera = session;
        if (!model.isReview()) {
            screen.cameraTitle.setText("实时打分");
            screen.takePhoto.setVisibility(View.GONE);
            screen.liveScore.setVisibility(View.VISIBLE);
            session.startLive(this, screen.preview, score -> {
                screen.liveScore.setText(getString(R.string.score_format, score.value(), score.maximum()));
                screen.cameraStatus.setText(score.example() ? R.string.sample_score : R.string.model_score);
            }, message -> {
                screen.liveScore.setText(R.string.live_score_empty);
                screen.cameraStatus.setText(message);
            }, model::showError);
            return;
        }
        screen.takePhoto.setOnClickListener(view -> {
            screen.takePhoto.setEnabled(false);
            screen.cameraStatus.setText(R.string.taking_photo);
            session.takePicture(model::analyzeCapture, model::showError);
        });
        session.start(this, screen.preview, boxes -> {
            screen.faceOverlay.setPreviewBoxes(boxes);
            if (!screen.takePhoto.isEnabled()) return;
            int message = switch (boxes.size()) {
                case 0 -> R.string.camera_waiting_for_face;
                case 1 -> R.string.camera_single_face;
                default -> R.string.camera_multiple_faces;
            };
            if (!Integer.valueOf(message).equals(screen.cameraStatus.getTag())) {
                screen.cameraStatus.setText(message);
                screen.cameraStatus.setTag(message);
            }
        }, () -> {
            screen.cameraStatus.setText(R.string.camera_waiting_for_face);
            screen.takePhoto.setEnabled(true);
        }, model::showError);
    }

    private void showStatus(FaceScoreViewModel.State state) {
        ScreenStatusBinding screen = ScreenStatusBinding.inflate(getLayoutInflater(), layout.screen, true);
        boolean loading = state.page() == FaceScoreViewModel.Page.ANALYZING;
        screen.title.setText(loading ? R.string.analyzing : R.string.analysis_error_title);
        screen.progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        screen.detail.setVisibility(loading ? View.GONE : View.VISIBLE);
        screen.choosePhoto.setVisibility(loading ? View.GONE : View.VISIBLE);
        if (loading && state.text() != null) screen.title.setText(state.text());
        if (!loading && state.text() != null) screen.detail.setText(state.text());
        if (!loading && state.text() == null) screen.detail.setText(state.errorRes());
        if (!loading && !model.hasSession()) {
            screen.choosePhoto.setText("返回账号页面");
            screen.choosePhoto.setOnClickListener(view -> model.showAuth(model.isRegistering()));
            return;
        }
        screen.choosePhoto.setOnClickListener(view -> choosePhoto());
    }

    private void showResult(FaceAnalyzer.Result result) {
        ScreenResultBinding screen = ScreenResultBinding.inflate(getLayoutInflater(), layout.screen, true);
        screen.photo.setImageBitmap(result.photo());
        screen.faceOverlay.setPhotoBox(result.bounds(), result.photo().getWidth(), result.photo().getHeight(), result.marks());
        FaceScorer.Score score = result.score();
        screen.scoreLabel.setText(score.example() ? R.string.sample_score : R.string.model_score);
        screen.scoreValue.setText(getString(R.string.score_format, score.value(), score.maximum()));
        screen.scoreScale.setMax(score.maximum() * 10);
        screen.scoreScale.setProgress(Math.round(score.value() * 10));
        screen.scoreNote.setVisibility(score.example() ? View.VISIBLE : View.GONE);
        FaceAnalyzer.Measurements measures = result.measures();
        Float ratio = measures.eyeRatio();
        screen.eyeRatio.setText(ratio == null ? getString(R.string.eye_ratio_unavailable)
                : getString(R.string.eye_ratio_format, ratio * 100f));
        screen.headAngle.setText(getString(R.string.yaw_format, measures.yaw()));
        screen.rollAngle.setText(getString(R.string.roll_format, measures.roll()));
        setRatio(screen.mouthRatio, R.string.mouth_ratio_format, measures.mouthRatio());
        setRatio(screen.noseRatio, R.string.nose_ratio_format, measures.noseToLipRatio());
        screen.choosePhoto.setOnClickListener(view -> choosePhoto());
    }

    private void setRatio(android.widget.TextView view, int label, Float value) {
        view.setVisibility(value == null ? View.GONE : View.VISIBLE);
        if (value != null) view.setText(getString(label, value * 100f));
    }

    private void closeCamera() {
        if (camera != null) {
            camera.close();
            camera = null;
        }
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        super.onDestroy();
    }
}
