package com.onlineexam.security;

import java.util.concurrent.locks.ReentrantLock;

/** Single-instance lock shared by login registration and supervised session recovery. */
public final class LoginRegistrationLock {
    private final ReentrantLock lock = new ReentrantLock();

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
