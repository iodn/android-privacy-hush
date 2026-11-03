package org.kaijinlab.hush.root;
import android.content.ComponentName;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.List;

/** Only emits Maps visibility; task intents and destinations never leave this process. */
public final class MapsTaskCommand {
    private static final String MAPS = "com.google.android.apps.maps";
    private static boolean maps(Object info) throws Exception {
        if (info == null) return false;
        ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
        return top != null && MAPS.equals(top.getPackageName());
    }
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 && Process.myUid() != 2000) throw new SecurityException("Privileged task access required");
            String apiName = Build.VERSION.SDK_INT >= 29 ? "android.app.IActivityTaskManager" : "android.app.IActivityManager";
            String service = Build.VERSION.SDK_INT >= 29 ? "activity_task" : "activity";
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, service);
            Object manager = Class.forName(apiName + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> api = Class.forName(apiName);
            Method focused, all;
            try { focused = api.getMethod("getFocusedRootTaskInfo"); all = api.getMethod("getAllRootTaskInfos"); }
            catch (NoSuchMethodException old) { focused = api.getMethod("getFocusedStackInfo"); all = api.getMethod("getAllStackInfos"); }
            if (maps(focused.invoke(manager))) { System.out.println("MAPS"); System.exit(0); }
            for (Object info : (List<?>) all.invoke(manager)) {
                if (!maps(info)) continue;
                boolean visible;
                try { visible = info.getClass().getField("isVisible").getBoolean(info); }
                catch (NoSuchFieldException old) { visible = info.getClass().getField("visible").getBoolean(info); }
                if (visible) { System.out.println("MAPS"); System.exit(0); }
            }
            System.out.println("OTHER");
            System.exit(0);
        } catch (Throwable e) {
            System.err.println("Maps visibility unavailable: " + (e.getCause() == null ? e : e.getCause()));
            System.exit(1);
        }
    }
}
