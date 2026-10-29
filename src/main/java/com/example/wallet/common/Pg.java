package com.example.wallet.common;

import java.sql.SQLException;

public final class Pg {
    private Pg() {}
    /** SQLSTATE 55P03: lock_not_available (NOWAIT or lock_timeout hit). */
    public static boolean isLockNotAvailable(Throwable e) {
        for (; e != null; e = e.getCause()) {
            if (e instanceof SQLException s && "55P03".equals(s.getSQLState())) return true;
        }
        return false;
    }
}
