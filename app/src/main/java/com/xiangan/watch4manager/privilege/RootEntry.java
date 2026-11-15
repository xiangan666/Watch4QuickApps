package com.xiangan.watch4manager.privilege;

import org.json.JSONObject;

/** app_process 入口，敏感结果只写给父进程的私有管道。 */
public final class RootEntry {
    private RootEntry() {}
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new IllegalStateException("Root 未授权");
            if (args.length != 2) throw new IllegalArgumentException("参数错误");
            int userId = Integer.parseInt(args[1]);
            if (args[0].equals("import")) System.out.print(PairingReader.read(userId));
            else if (args[0].equals("stop")) System.out.print(PairingReader.stop(userId) ? "ok" : "failed");
            else if (args[0].equals("diagnose")) System.out.print(PairingReader.diagnose(userId));
            else throw new IllegalArgumentException("不支持的操作");
        } catch (Throwable e) {
            try { System.out.print(new JSONObject().put("error", e.getMessage()).toString()); }
            catch (Exception ignored) { System.out.print("{\"error\":\"读取失败\"}"); }
            System.exit(1);
        }
    }
}
