package app.outerheaven.compute;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.util.Base64;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

public class MainActivity extends Activity {
    private String token;
    private TextView info;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        token = getSharedPreferences("ohc", 0).getString("token", null);
        if (token == null) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            token = Base64.encodeToString(bytes,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            getSharedPreferences("ohc", 0).edit()
                .putString("token", token).apply();
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 36, 32, 24);

        TextView title = new TextView(this);
        title.setText("OUTER HEAVEN COMPUTE");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 24);
        layout.addView(title);

        info = new TextView(this);
        info.setTextSize(15);
        info.setTextIsSelectable(true);
        layout.addView(info);

        Button start = new Button(this);
        start.setText("INICIAR SERVIDOR");
        layout.addView(start);
        start.setOnClickListener(v -> {
            Intent i = new Intent(this, ComputeService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            info.setText(getInfo() + "\n\nServidor iniciado na porta 8765.");
        });

        Button stop = new Button(this);
        stop.setText("PARAR SERVIDOR");
        layout.addView(stop);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ComputeService.class));
            info.setText(getInfo() + "\n\nServidor parado.");
        });

        Button copy = new Button(this);
        copy.setText("COPIAR TOKEN DE ACESSO");
        layout.addView(copy);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cb =
                (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("OHC Token", token));
            Toast.makeText(this, "Token copiado", Toast.LENGTH_SHORT).show();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        info.setText(getInfo());
    }

    private String getInfo() {
        return "Dispositivo: " + Build.MODEL +
            "\nAndroid: " + Build.VERSION.RELEASE +
            "\nCPU: " + Runtime.getRuntime().availableProcessors() + " núcleos" +
            "\nEndereço: " + findIp() +
            "\nPorta: 8765" +
            "\n\nToken de acesso:\n" + token +
            "\n\nCompartilhamento protegido por token.";
    }

    private String findIp() {
        try {
            Enumeration<NetworkInterface> nets =
                NetworkInterface.getNetworkInterfaces();
            String fallback = "IP não encontrado";
            while (nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (!net.isUp() || net.isLoopback()) continue;
                Enumeration<InetAddress> addresses = net.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if ("wlan0".equals(net.getName())) return a.getHostAddress();
                        fallback = a.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return "IP indisponível";
        }
    }
}
