fun foo(o: Any) {
    <caret>val a = 1
}

// INVOCATION_COUNT: 1
// EXIST: read, write

// RUNTIME_TYPE: java.util.concurrent.locks.ReentrantReadWriteLock