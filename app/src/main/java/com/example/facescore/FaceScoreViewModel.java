package com.example.facescore;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class FaceScoreViewModel extends AndroidViewModel {
    static final int MAX_IMAGE_EDGE = 1600;

    enum Page { LOGIN, REGISTER, HOME, INPUT, CAMERA, ANALYZING, RESULT, REVIEW, ERROR }

    record State(Page page, FaceAnalyzer.Result result, @StringRes int errorRes, String text) {
        State(Page page, FaceAnalyzer.Result result, int errorRes) {
            this(page, result, errorRes, null);
        }
    }

    private boolean reviewMode;
    private String session;
    private boolean registering;

    boolean isReview() { return reviewMode; }
    boolean hasSession() { return session != null; }

    void selectMode(boolean review) {
        if (!hasSession()) return;
        reviewMode = review;
        showInput();
    }

    void showInput() {
        cancelPending();
        state.setValue(new State(hasSession() ? Page.INPUT : Page.LOGIN, null, 0));
    }

    void showAuth(boolean register) {
        cancelPending();
        registering = register;
        state.setValue(new State(register ? Page.REGISTER : Page.LOGIN, null, 0));
    }

    boolean isRegistering() { return registering; }

    void authenticate(String name, String password, boolean register) {
        cancelPending();
        int request = generation;
        state.setValue(new State(Page.ANALYZING, null, 0, "正在连接账号服务…"));
        pending = worker.submit(() -> {
            try {
                String token = BackendClient.signIn(name, password, register);
                main.execute(() -> {
                    if (request != generation) return;
                    session = token;
                    state.setValue(new State(Page.HOME, null, 0));
                });
            } catch (BackendClient.ApiException error) {
                publish(request, new State(Page.ERROR, null, 0, error.getMessage()));
            } catch (Exception error) {
                publish(request, new State(Page.ERROR, null, 0, "连接服务器失败，请稍后重试。"));
            }
        });
    }

    private final LocalFaceScorer scorer;
    private final FaceAnalyzer analyzer;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(Page.LOGIN, null, 0));
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Executor main;
    private Future<?> pending;
    private int generation;

    public FaceScoreViewModel(@NonNull Application application) {
        super(application);
        scorer = new LocalFaceScorer(application.getAssets());
        analyzer = new FaceAnalyzer(scorer);
        main = ContextCompat.getMainExecutor(application);
    }

    LiveData<State> state() {
        return state;
    }

    void showHome() {
        cancelPending();
        state.setValue(new State(hasSession() ? Page.HOME : Page.LOGIN, null, 0));
    }

    void openCamera() {
        if (!hasSession() || state.getValue().page() != Page.INPUT) return;
        cancelPending();
        state.setValue(new State(Page.CAMERA, null, 0));
    }

    void showError(@StringRes int messageRes) {
        cancelPending();
        state.setValue(new State(Page.ERROR, null, messageRes));
    }

    void analyzePhoto(Uri uri) {
        Page page = state.getValue().page();
        if (page != Page.INPUT && page != Page.RESULT && page != Page.REVIEW) return;
        analyze(() -> {
            ImageDecoder.Source source = ImageDecoder.createSource(getApplication().getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                float scale = imageScale(width, height);
                if (scale < 1f) {
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                }
            });
        });
    }

    void analyzeCapture(Bitmap photo) {
        if (!reviewMode || state.getValue().page() != Page.CAMERA) return;
        analyze(() -> photo);
    }

    private void analyze(PhotoSource source) {
        if (!hasSession()) return;
        cancelPending();
        int request = generation;
        state.setValue(new State(Page.ANALYZING, null, 0, reviewMode ? "正在生成照片锐评…" : null));
        String token = reviewMode ? session : null;
        pending = worker.submit(() -> {
            try {
                Bitmap photo = source.read();
                if (Thread.currentThread().isInterrupted()) return;
                if (token != null) {
                    publish(request, new State(Page.REVIEW, null, 0, BackendClient.review(photo, token)));
                } else {
                    publish(request, new State(Page.RESULT, analyzer.analyze(photo), 0));
                }
            } catch (BackendClient.ApiException error) {
                main.execute(() -> {
                    if (request != generation) return;
                    if (error.status == 401) session = null;
                    state.setValue(new State(Page.ERROR, null, 0, error.getMessage()));
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (FaceAnalyzer.AnalysisException error) {
                publish(request, new State(Page.ERROR, null, error.messageRes));
            } catch (Exception error) {
                publish(request, new State(Page.ERROR, null, R.string.analysis_failed));
            }
        });
    }

    private void publish(int request, State updated) {
        main.execute(() -> {
            if (request == generation) {
                state.setValue(updated);
            }
        });
    }

    private void cancelPending() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
            pending = null;
        }
    }

    static float imageScale(int width, int height) {
        return Math.min(1f, (float) MAX_IMAGE_EDGE / Math.max(width, height));
    }

    @Override
    protected void onCleared() {
        cancelPending();
        worker.execute(() -> {
            analyzer.close();
            scorer.close();
        });
        worker.shutdown();
    }

    private interface PhotoSource {
        Bitmap read() throws IOException;
    }
}
