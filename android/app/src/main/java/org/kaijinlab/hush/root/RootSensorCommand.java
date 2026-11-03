package org.kaijinlab.hush.root;

import android.os.IBinder;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Root-only Binder calls; no ActivityThread or framework caches in the short-lived process. */
public final class RootSensorCommand {
    private static Object sensors, users;
    private static Class<?> sensorApi, userApi;
    private static String key;
    private static Object service(String name, String api) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(api + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    private static boolean toggleBlocked(int sensor) throws Exception {
        try { return (Boolean) sensorApi.getMethod("isCombinedToggleSensorPrivacyEnabled", int.class).invoke(sensors, sensor); }
        catch (NoSuchMethodException old) { return (Boolean) sensorApi.getMethod("isIndividualSensorPrivacyEnabled", int.class, int.class).invoke(sensors, 0, sensor); }
    }
    private static boolean restriction(String method, int user) throws Exception {
        return (Boolean) userApi.getMethod(method, String.class, int.class).invoke(users, key, user);
    }
    private static List<String> profiles() throws Exception {
        List<?> profiles = (List<?>) userApi.getMethod("getProfiles", int.class, boolean.class).invoke(users, 0, false);
        List<String> ids = new ArrayList<>();
        for (Object p : profiles) ids.add(p.getClass().getField("id").get(p) + ":" + p.getClass().getField("serialNumber").get(p));
        if (ids.isEmpty()) throw new IllegalStateException("No user profiles available");
        return ids;
    }
    private static int userId(String identity) { return Integer.parseInt(identity.split(":")[0]); }
    private static void write(List<String> ids, boolean blocked) throws Exception {
        for (String id : ids) {
            int user = userId(id);
            userApi.getMethod("setUserRestriction", String.class, boolean.class, int.class).invoke(users, key, blocked, user);
            if (restriction("hasBaseUserRestriction", user) != blocked) throw new IllegalStateException("Restriction was not applied for user " + user);
        }
    }
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 || args.length < 2 || args.length > 3) throw new IllegalArgumentException("Root and sensor arguments required");
            boolean camera = args[1].equals("camera");
            if (!camera && !args[1].equals("microphone")) throw new IllegalArgumentException("Unknown sensor");
            int sensor = camera ? 2 : 1;
            key = camera ? "no_camera" : "no_record_audio";
            sensorApi = Class.forName("android.hardware.ISensorPrivacyManager");
            sensors = service("sensor_privacy", sensorApi.getName());
            userApi = Class.forName("android.os.IUserManager");
            users = service("user", userApi.getName());
            List<String> profiles = profiles();
            List<String> selected = new ArrayList<>();
            if (args.length == 3) for (String id : args[2].split(",")) {
                if (!id.matches("[0-9]+:[0-9]+")) throw new IllegalArgumentException("Invalid profile identity");
                if (profiles.contains(id)) selected.add(id); // Serial numbers prevent clearing a replacement profile's policies.
            }
            String result = "OK";
            switch (args[0]) {
                case "read":
                    boolean all = (Boolean) sensorApi.getMethod("isSensorPrivacyEnabled").invoke(sensors);
                    boolean blocked = true;
                    for (String id : profiles) blocked &= restriction("hasUserRestriction", userId(id));
                    result = all || toggleBlocked(sensor) || blocked ? "BLOCKED" : "ALLOWED";
                    break;
                case "targets":
                    for (String id : profiles) if (!restriction("hasUserRestriction", userId(id))) selected.add(id);
                    result = selected.isEmpty() ? "NONE" : String.join(",", selected);
                    break;
                case "base":
                    List<String> remaining = new ArrayList<>();
                    for (String id : selected) if (restriction("hasBaseUserRestriction", userId(id))) remaining.add(id);
                    result = remaining.isEmpty() ? "NONE" : String.join(",", remaining);
                    break;
                case "block": write(selected, true); break;
                case "allow": write(selected, false); break;
                case "handoff":
                    Object windows = service("window", "android.view.IWindowManager");
                    if ((Boolean) Class.forName("android.view.IWindowManager").getMethod("isKeyguardLocked").invoke(windows)) throw new IllegalStateException("Phone is locked");
                    Method set;
                    try { set = sensorApi.getMethod("setToggleSensorPrivacy", int.class, int.class, int.class, boolean.class); }
                    catch (NoSuchMethodException old) { set = sensorApi.getMethod("setIndividualSensorPrivacy", int.class, int.class, int.class, boolean.class); }
                    set.invoke(sensors, 0, 5, sensor, true);
                    if (!toggleBlocked(sensor)) throw new IllegalStateException("Privacy not confirmed; retaining restrictions");
                    write(selected, false);
                    break;
                default: throw new IllegalArgumentException("Unknown sensor action");
            }
            System.out.println(result);
            System.exit(0);
        } catch (Throwable error) {
            System.err.println("Sensor command failed: " + (error.getCause() == null ? error : error.getCause()));
            System.exit(1);
        }
    }
}
