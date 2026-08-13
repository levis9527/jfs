package com.levis9527.jfs.proxy;

final class UnauthorizedException extends RuntimeException {
    UnauthorizedException() {
        super("unauthorized");
    }
}
