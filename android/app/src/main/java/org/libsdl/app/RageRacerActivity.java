package org.libsdl.app;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class RageRacerActivity extends SDLActivity implements SensorEventListener {

    public static native void nativeSetVirtualPad(int pressed, int released);

    // Mapeamento dos botões do controle PS1
    public static final int PAD_L2       = 0x0001; // Reduzir marcha
    public static final int PAD_R2       = 0x0002; // Aumentar marcha
    public static final int PAD_L1       = 0x0004; // Retrovisor esquerdo
    public static final int PAD_R1       = 0x0008; // Retrovisor direito
    public static final int PAD_TRIANGLE = 0x0010; // Trocar câmera / Voltar menu
    public static final int PAD_CIRCLE   = 0x0020; // Ação secundária
    public static final int PAD_CROSS    = 0x0040; // Acelerar / Confirmar menu
    public static final int PAD_SQUARE   = 0x0080; // Frear / Marcha ré
    public static final int PAD_SELECT   = 0x0100; // Select
    public static final int PAD_START    = 0x0800; // Pausa / Start
    public static final int PAD_UP       = 0x1000; // Cima no menu / Câmbio
    public static final int PAD_RIGHT    = 0x2000; // Virar à direita
    public static final int PAD_DOWN     = 0x4000; // Baixo no menu / Câmbio
    public static final int PAD_LEFT     = 0x8000; // Virar à esquerda

    private RelativeLayout mTouchOverlay;
    private Vibrator mVibrator;
    private float mCurrentAlpha = 0.75f;
    private static final String DISC_FILENAME = "Rage Racer.chd";

    // Sensor / Giroscópio
    private SensorManager mSensorManager;
    private Sensor mGravitySensor;
    private boolean mGyroSteeringEnabled = false;
    private Button mBtnGyroToggle;
    private int mLastGyroDirection = 0; // -1: ESQUERDA, 0: RETO, 1: DIREITA
    private static final float STEER_DEADZONE_DEG = 4.5f;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "main"
        };
    }

    @Override
    protected String[] getArguments() {
        File discFile = getDiscFile();
        if (discFile != null && discFile.exists()) {
            return new String[] {
                "--set", "disc.image=" + discFile.getAbsolutePath(),
                "--set", "modern.aspect=21:9",
                "--set", "modern.internal_scale=2.0",
                "--set", "modern.post=none",
                "--set", "modern.fps=vsync"
            };
        }
        return new String[] {
            "--set", "modern.aspect=21:9",
            "--set", "modern.internal_scale=2.0",
            "--set", "modern.post=none",
            "--set", "modern.fps=vsync"
        };
    }

    private File getDiscFile() {
        File extDir = getExternalFilesDir(null);
        if (extDir != null) {
            File disc = new File(extDir, DISC_FILENAME);
            if (disc.exists()) return disc;
        }
        File intDir = getFilesDir();
        if (intDir != null) {
            return new File(intDir, DISC_FILENAME);
        }
        return null;
    }

    @Override
    public void setRequestedOrientation(int requestedOrientation) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        if (window == null) return;

        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        View decor = window.getDecorView();
        if (decor != null) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideSystemBars();
                    }
                }, 300);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        super.onCreate(savedInstanceState);
        hideSystemBars();

        View decor = getWindow().getDecorView();
        if (decor != null) {
            decor.post(new Runnable() {
                @Override
                public void run() {
                    hideSystemBars();
                }
            });
        }

        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (mSensorManager != null) {
            mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (mGravitySensor == null) {
                mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }

        checkAndInstallDiscAssets();
        setupTouchControls();
    }

    private void safeRegisterSensor() {
        if (mSensorManager != null && mGravitySensor != null) {
            try {
                mSensorManager.registerListener(this, mGravitySensor, 10000); // 10ms (100Hz)
            } catch (Exception e1) {
                try {
                    mSensorManager.registerListener(this, mGravitySensor, SensorManager.SENSOR_DELAY_GAME);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (mGyroSteeringEnabled) {
            safeRegisterSensor();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
        if (mLastGyroDirection != 0) {
            nativeSetVirtualPad(0, PAD_LEFT | PAD_RIGHT);
            mLastGyroDirection = 0;
        }
    }

    // --- SENSORES: VOLANTE POR GRAVIDADE / INCLINAÇÃO (ZERO LATÊNCIA) ---
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!mGyroSteeringEnabled) {
            return;
        }

        int type = event.sensor.getType();
        if (type != Sensor.TYPE_GRAVITY && type != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        float gx = event.values[0];
        float gy = event.values[1];

        int rotation = Surface.ROTATION_90;
        try {
            if (getWindowManager() != null && getWindowManager().getDefaultDisplay() != null) {
                rotation = getWindowManager().getDefaultDisplay().getRotation();
            }
        } catch (Exception ignored) {}

        // Em landscape (ROTATION_90 padrão):
        // gx aponta para o topo da tela (+X).
        // gy aponta para o lado esquerdo da tela (+Y).
        // Ao virar como volante para a DIREITA (horário): mão esquerda sobe, gy > 0.
        // Ao virar para a ESQUERDA (anti-horário): mão direita sobe, gy < 0.
        // Math.atan2(gy, gx) calcula o ângulo de esterçamento puro em graus.
        // A inclinação do celular (pitch) cancela na razão gy/gx = tan(volante),
        // funcionando com exatidão sem precisar levantar o celular na vertical.
        float angleDeg;
        float screenUp;
        if (rotation == Surface.ROTATION_270) {
            angleDeg = (float) Math.toDegrees(Math.atan2(-gy, -gx));
            screenUp = -gx;
        } else {
            angleDeg = (float) Math.toDegrees(Math.atan2(gy, gx));
            screenUp = gx;
        }

        int newDirection = 0;
        float tiltMagnitude = (float) Math.hypot(gx, gy);

        // Se o celular estiver quase reto na mesa (pitch quase 0) ou de ponta-cabeça, neutraliza
        if (tiltMagnitude > 1.5f && screenUp > 0.0f) {
            if (angleDeg > STEER_DEADZONE_DEG) {
                newDirection = 1; // DIREITA -> PAD_RIGHT
            } else if (angleDeg < -STEER_DEADZONE_DEG) {
                newDirection = -1; // ESQUERDA -> PAD_LEFT
            }
        }

        if (newDirection != mLastGyroDirection) {
            int pressed = 0;
            int released = 0;

            if (mLastGyroDirection == -1) released |= PAD_LEFT;
            if (mLastGyroDirection == 1) released |= PAD_RIGHT;

            if (newDirection == -1) pressed |= PAD_LEFT;
            if (newDirection == 1) pressed |= PAD_RIGHT;

            nativeSetVirtualPad(pressed, released);
            mLastGyroDirection = newDirection;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void checkAndInstallDiscAssets() {
        final File target = getDiscFile();
        if (target != null && target.exists() && target.length() > 1000000) {
            return;
        }

        try {
            String[] assets = getAssets().list("");
            boolean found = false;
            if (assets != null) {
                for (String a : assets) {
                    if (DISC_FILENAME.equalsIgnoreCase(a)) {
                        found = true;
                        break;
                    }
                }
            }
            if (found) {
                new ExtractAssetsTask().execute();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class ExtractAssetsTask extends AsyncTask<Void, Integer, Boolean> {
        private ProgressDialog dialog;

        @Override
        protected void onPreExecute() {
            try {
                dialog = new ProgressDialog(RageRacerActivity.this);
                dialog.setTitle("Instalando Rage Racer");
                dialog.setMessage("Extraindo dados do jogo... Isso ocorre apenas no primeiro início.");
                dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                dialog.setCancelable(false);
                dialog.show();
            } catch (Exception ignored) {}
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            File target = getDiscFile();
            if (target == null) {
                File dir = getFilesDir();
                target = new File(dir, DISC_FILENAME);
            }
            try (InputStream in = getAssets().open(DISC_FILENAME);
                 OutputStream out = new FileOutputStream(target)) {

                int totalBytes = in.available();
                if (totalBytes <= 0) totalBytes = 496000000;
                if (dialog != null) dialog.setMax(100);

                byte[] buffer = new byte[256 * 1024];
                long copied = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    copied += read;
                    int percent = (int) ((copied * 100L) / totalBytes);
                    publishProgress(Math.min(percent, 99));
                }
                out.flush();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.setProgress(values[0]);
                }
            } catch (Exception ignored) {}
        }

        @Override
        protected void onPostExecute(Boolean success) {
            try {
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
            } catch (Exception ignored) {}

            if (!success) {
                Toast.makeText(RageRacerActivity.this,
                        "Erro ao extrair arquivos do disco. Verifique espaço de armazenamento.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(RageRacerActivity.this,
                        "Instalação concluída com sucesso! Bom jogo!",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setupTouchControls() {
        if (mLayout == null) return;

        mTouchOverlay = new RelativeLayout(this);
        RelativeLayout.LayoutParams overlayParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        mTouchOverlay.setLayoutParams(overlayParams);

        // --- PAINEL ESQUERDO: D-PAD EM CRUZ CLÁSSICA (52dp CADA BOTÃO) ---
        RelativeLayout leftPanel = new RelativeLayout(this);
        int dpadContainerSize = dpToPx(160);
        RelativeLayout.LayoutParams leftParams = new RelativeLayout.LayoutParams(
                dpadContainerSize, dpadContainerSize
        );
        leftParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        leftParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        leftParams.leftMargin = dpToPx(16);
        leftParams.bottomMargin = dpToPx(14);
        leftPanel.setLayoutParams(leftParams);

        int dpadBtnSize = dpToPx(52);
        int dpadFill = Color.argb(125, 18, 28, 48);
        int dpadStroke = Color.argb(220, 0, 229, 255);

        // ▲ CIMA
        Button btnUp = createCircularButton("▲", PAD_UP, dpadFill, dpadStroke, 18, dpadBtnSize);
        RelativeLayout.LayoutParams upParams = new RelativeLayout.LayoutParams(dpadBtnSize, dpadBtnSize);
        upParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        upParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        btnUp.setLayoutParams(upParams);

        // ▼ BAIXO
        Button btnDown = createCircularButton("▼", PAD_DOWN, dpadFill, dpadStroke, 18, dpadBtnSize);
        RelativeLayout.LayoutParams downParams = new RelativeLayout.LayoutParams(dpadBtnSize, dpadBtnSize);
        downParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        downParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        btnDown.setLayoutParams(downParams);

        // ◀ ESQUERDA
        Button btnLeft = createCircularButton("◀", PAD_LEFT, dpadFill, dpadStroke, 18, dpadBtnSize);
        RelativeLayout.LayoutParams leftBtnParams = new RelativeLayout.LayoutParams(dpadBtnSize, dpadBtnSize);
        leftBtnParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        leftBtnParams.addRule(RelativeLayout.CENTER_VERTICAL);
        btnLeft.setLayoutParams(leftBtnParams);

        // ▶ DIREITA
        Button btnRight = createCircularButton("▶", PAD_RIGHT, dpadFill, dpadStroke, 18, dpadBtnSize);
        RelativeLayout.LayoutParams rightBtnParams = new RelativeLayout.LayoutParams(dpadBtnSize, dpadBtnSize);
        rightBtnParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightBtnParams.addRule(RelativeLayout.CENTER_VERTICAL);
        btnRight.setLayoutParams(rightBtnParams);

        leftPanel.addView(btnUp);
        leftPanel.addView(btnDown);
        leftPanel.addView(btnLeft);
        leftPanel.addView(btnRight);

        // --- PAINEL CENTRAL: SELECT & START CLÁSSICOS PLAYSTATION ---
        LinearLayout centerPanel = new LinearLayout(this);
        centerPanel.setOrientation(LinearLayout.HORIZONTAL);
        RelativeLayout.LayoutParams centerParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        centerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        centerParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        centerParams.bottomMargin = dpToPx(16);
        centerPanel.setLayoutParams(centerParams);

        Button btnSelect = createPillButton("SELECT", PAD_SELECT,
                Color.argb(120, 35, 35, 45), Color.argb(230, 200, 205, 220), 11);
        Button btnStart = createPillButton("START", PAD_START,
                Color.argb(140, 65, 20, 25), Color.argb(255, 255, 82, 82), 11);

        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startParams.leftMargin = dpToPx(18);
        btnStart.setLayoutParams(startParams);

        centerPanel.addView(btnSelect);
        centerPanel.addView(btnStart);

        // --- PAINEL DIREITO: CLUSTER PLAYSTATION (✕, □, △, ○) + PADDLES (R2/L2) ---
        RelativeLayout rightPanel = new RelativeLayout(this);
        RelativeLayout.LayoutParams rightParams = new RelativeLayout.LayoutParams(
                dpToPx(210), dpToPx(180)
        );
        rightParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        rightParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        rightParams.rightMargin = dpToPx(16);
        rightParams.bottomMargin = dpToPx(12);
        rightPanel.setLayoutParams(rightParams);

        // Diamante de Ação (4 botões PlayStation)
        RelativeLayout actionCluster = new RelativeLayout(this);
        actionCluster.setId(View.generateViewId());
        int clusterW = dpToPx(148);
        int clusterH = dpToPx(136);
        RelativeLayout.LayoutParams clusterParams = new RelativeLayout.LayoutParams(clusterW, clusterH);
        clusterParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        clusterParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        actionCluster.setLayoutParams(clusterParams);

        // ✕ ACELERAR (Azul Neon - 52dp)
        int btnCrossSize = dpToPx(52);
        Button btnGas = createCircularButton("✕", PAD_CROSS,
                Color.argb(140, 10, 45, 80), Color.argb(255, 0, 229, 255), 22, btnCrossSize);
        RelativeLayout.LayoutParams gasParams = new RelativeLayout.LayoutParams(btnCrossSize, btnCrossSize);
        gasParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        gasParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        btnGas.setLayoutParams(gasParams);

        // □ FREAR (Rosa/Vermelho Neon - 50dp)
        int btnSquareSize = dpToPx(50);
        Button btnBrake = createCircularButton("□", PAD_SQUARE,
                Color.argb(140, 70, 15, 30), Color.argb(255, 255, 23, 68), 22, btnSquareSize);
        RelativeLayout.LayoutParams brakeParams = new RelativeLayout.LayoutParams(btnSquareSize, btnSquareSize);
        brakeParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        brakeParams.addRule(RelativeLayout.CENTER_VERTICAL);
        btnBrake.setLayoutParams(brakeParams);

        // ○ RÉ / CONFIRMAR (Vermelho/Laranja Neon - 46dp)
        int btnCircleSize = dpToPx(46);
        Button btnCircle = createCircularButton("○", PAD_CIRCLE,
                Color.argb(130, 60, 35, 15), Color.argb(255, 255, 110, 64), 20, btnCircleSize);
        RelativeLayout.LayoutParams circleParams = new RelativeLayout.LayoutParams(btnCircleSize, btnCircleSize);
        circleParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        circleParams.addRule(RelativeLayout.CENTER_VERTICAL);
        btnCircle.setLayoutParams(circleParams);

        // △ CÂMERA (Verde Neon - 46dp)
        int btnTriSize = dpToPx(46);
        Button btnCamera = createCircularButton("△", PAD_TRIANGLE,
                Color.argb(130, 15, 55, 30), Color.argb(255, 0, 230, 118), 18, btnTriSize);
        RelativeLayout.LayoutParams camParams = new RelativeLayout.LayoutParams(btnTriSize, btnTriSize);
        camParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        camParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        btnCamera.setLayoutParams(camParams);

        actionCluster.addView(btnGas);
        actionCluster.addView(btnBrake);
        actionCluster.addView(btnCircle);
        actionCluster.addView(btnCamera);

        // Câmbio Borboleta (Paddle Shifters R2 ▲ e L2 ▼)
        int paddleW = dpToPx(46);
        int paddleH = dpToPx(30);

        Button btnGearUp = createPillButton("R2 ▲", PAD_R2,
                Color.argb(110, 45, 45, 65), Color.argb(220, 255, 215, 0), 10);
        RelativeLayout.LayoutParams gearUpParams = new RelativeLayout.LayoutParams(paddleW, paddleH);
        gearUpParams.addRule(RelativeLayout.ABOVE, actionCluster.getId());
        gearUpParams.addRule(RelativeLayout.ALIGN_RIGHT, actionCluster.getId());
        gearUpParams.bottomMargin = dpToPx(6);
        btnGearUp.setLayoutParams(gearUpParams);

        Button btnGearDown = createPillButton("L2 ▼", PAD_L2,
                Color.argb(110, 45, 45, 65), Color.argb(220, 255, 215, 0), 10);
        RelativeLayout.LayoutParams gearDownParams = new RelativeLayout.LayoutParams(paddleW, paddleH);
        gearDownParams.addRule(RelativeLayout.ABOVE, actionCluster.getId());
        gearDownParams.addRule(RelativeLayout.ALIGN_LEFT, actionCluster.getId());
        gearDownParams.bottomMargin = dpToPx(6);
        btnGearDown.setLayoutParams(gearDownParams);

        Button btnRear = createPillButton("R1", PAD_R1,
                Color.argb(100, 30, 30, 50), Color.argb(200, 240, 240, 255), 9);
        RelativeLayout.LayoutParams rearParams = new RelativeLayout.LayoutParams(dpToPx(40), paddleH);
        rearParams.addRule(RelativeLayout.ABOVE, actionCluster.getId());
        rearParams.addRule(RelativeLayout.LEFT_OF, btnGearUp.getId());
        rearParams.rightMargin = dpToPx(6);
        rearParams.bottomMargin = dpToPx(6);
        btnRear.setLayoutParams(rearParams);

        rightPanel.addView(actionCluster);
        rightPanel.addView(btnGearUp);
        rightPanel.addView(btnGearDown);
        rightPanel.addView(btnRear);

        // --- BARRA SUPERIOR: APENAS UTILITÁRIOS (OPACIDADE E GIROSCÓPIO) ---
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        RelativeLayout.LayoutParams topParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        topParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        topParams.topMargin = dpToPx(20);
        topParams.leftMargin = dpToPx(24);
        topBar.setLayoutParams(topParams);

        final Button btnOpacity = createPillButton("👁️ VISÍVEL", 0,
                Color.argb(90, 20, 20, 30), Color.argb(140, 255, 255, 255), 10);
        btnOpacity.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrentAlpha > 0.65f) {
                    mCurrentAlpha = 0.40f;
                    btnOpacity.setText("👁️ 40%");
                } else if (mCurrentAlpha > 0.35f) {
                    mCurrentAlpha = 0.15f;
                    btnOpacity.setText("👁️ 15%");
                } else if (mCurrentAlpha > 0.10f) {
                    mCurrentAlpha = 0.0f;
                    btnOpacity.setText("👁️ 0%");
                } else {
                    mCurrentAlpha = 0.75f;
                    btnOpacity.setText("👁️ VISÍVEL");
                }
                mTouchOverlay.setAlpha(mCurrentAlpha);
            }
        });

        mBtnGyroToggle = createPillButton("🔄 GIRO: OFF", 0,
                Color.argb(90, 25, 25, 40), Color.argb(160, 200, 200, 255), 10);
        LinearLayout.LayoutParams gyroParams = (LinearLayout.LayoutParams) mBtnGyroToggle.getLayoutParams();
        if (gyroParams != null) gyroParams.leftMargin = dpToPx(10);
        mBtnGyroToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mGyroSteeringEnabled = !mGyroSteeringEnabled;
                if (mGyroSteeringEnabled) {
                    mBtnGyroToggle.setText("🔄 GIRO: ON");
                    applyButtonTheme(mBtnGyroToggle, Color.argb(140, 15, 60, 30), Color.argb(255, 0, 230, 118), dpToPx(15));
                    safeRegisterSensor();
                    Toast.makeText(RageRacerActivity.this, "Giroscópio Ativado: Incline para virar!", Toast.LENGTH_SHORT).show();
                } else {
                    mBtnGyroToggle.setText("🔄 GIRO: OFF");
                    applyButtonTheme(mBtnGyroToggle, Color.argb(90, 25, 25, 40), Color.argb(160, 200, 200, 255), dpToPx(15));
                    if (mSensorManager != null) {
                        mSensorManager.unregisterListener(RageRacerActivity.this);
                    }
                    if (mLastGyroDirection != 0) {
                        nativeSetVirtualPad(0, PAD_LEFT | PAD_RIGHT);
                        mLastGyroDirection = 0;
                    }
                    Toast.makeText(RageRacerActivity.this, "Giroscópio Desativado", Toast.LENGTH_SHORT).show();
                }
            }
        });

        topBar.addView(btnOpacity);
        topBar.addView(mBtnGyroToggle);

        mTouchOverlay.addView(leftPanel);
        mTouchOverlay.addView(centerPanel);
        mTouchOverlay.addView(rightPanel);
        mTouchOverlay.addView(topBar);

        mTouchOverlay.setAlpha(mCurrentAlpha);
        mLayout.addView(mTouchOverlay);
    }

    private Button createCircularButton(String text, final int mask, final int baseColor, final int strokeColor, int textSizeSp, int sizePx) {
        final Button btn = new Button(this);
        btn.setId(View.generateViewId());
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(strokeColor);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, 0, 0, 0);

        applyButtonTheme(btn, baseColor, strokeColor, sizePx / 2);

        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                    nativeSetVirtualPad(mask, 0);
                    btn.setAlpha(0.45f);
                    vibrateQuick(16);
                    return true;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    nativeSetVirtualPad(0, mask);
                    btn.setAlpha(1.0f);
                    return true;
                }
                return false;
            }
        });

        return btn;
    }

    private Button createPillButton(String text, final int mask, final int baseColor, final int strokeColor, int textSizeSp) {
        final Button btn = new Button(this);
        btn.setId(View.generateViewId());
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(strokeColor);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));

        applyButtonTheme(btn, baseColor, strokeColor, dpToPx(14));

        if (mask != 0) {
            btn.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                        nativeSetVirtualPad(mask, 0);
                        btn.setAlpha(0.45f);
                        vibrateQuick(16);
                        return true;
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        nativeSetVirtualPad(0, mask);
                        btn.setAlpha(1.0f);
                        return true;
                    }
                    return false;
                }
            });
        }

        return btn;
    }

    private void applyButtonTheme(View view, int fillColor, int strokeColor, int cornerRadiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(cornerRadiusPx);
        gd.setStroke(dpToPx(1.5f), strokeColor);
        view.setBackground(gd);
    }

    private void vibrateQuick(int millis) {
        if (mVibrator != null && mVibrator.hasVibrator()) {
            mVibrator.vibrate(millis);
        }
    }

    private int dpToPx(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
