package com.xiangan.watch4manager.privilege;

interface IPrivilegeService {
    void destroy() = 16777114;
    int uid() = 1;
    String readPairings(int androidUserId) = 2;
    boolean stopCompanion(int androidUserId) = 3;
}
