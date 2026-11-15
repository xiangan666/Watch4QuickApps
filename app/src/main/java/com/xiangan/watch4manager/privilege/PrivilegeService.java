package com.xiangan.watch4manager.privilege;

import android.os.RemoteException;

/** Shizuku UserService 只提供固定的两项操作，不接受 Shell 命令或任意路径。 */
public final class PrivilegeService extends IPrivilegeService.Stub {
    public PrivilegeService() {}
    @Override public void destroy() { System.exit(0); }
    @Override public int uid() { return android.os.Process.myUid(); }
    @Override public String readPairings(int androidUserId) throws RemoteException {
        try { return PairingReader.read(androidUserId); }
        catch (Exception e) { throw new RemoteException(e.getMessage()); }
    }
    @Override public boolean stopCompanion(int androidUserId) throws RemoteException {
        try { return PairingReader.stop(androidUserId); }
        catch (Exception e) { throw new RemoteException(e.getMessage()); }
    }
}
