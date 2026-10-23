package duke.kernel;

import duke.rt.Magic;
import duke.rt.Tib;

/**
 * A user program's threads, each a thread of this process from clone, which the kernel (Duke's or
 * Linux's) schedules: nothing here picks who runs. What's left is the bookkeeping java.lang.Thread
 * and Monitors need, and blocking, where a thread parks on a futex word of its own until another
 * unparks it.
 *
 * <p>Each thread has a slot: a mapping holding its stack, then its CPU block (GS points there),
 * then the word the kernel clears once the thread has exited (CLONE_CHILD_CLEARTID). Main has slot
 * 0, on _start's stack and block. There's no munmap yet, so a slot whose word is clear is reused
 * instead: nothing runs on that stack any more. The slot is also the thread's CPU index, which the
 * heap lock records its holder by, and Smp stops it by for a collection.
 *
 * <p>All of this is guarded by {@link #lock}, a futex lock. As in the kernel, nothing allocates
 * under it.
 */
public final class Scheduler {

    public static final int NEW = 0;
    public static final int RUNNING = 1;
    /** Waiting to enter a monitor. */
    public static final int BLOCKED = 2;
    /** In Object.wait. */
    public static final int WAITING = 3;
    public static final int DEAD = 4;

    /** One thread's state; java.lang.Thread holds one. */
    public static final class Task {
        final Thread thread;
        /** 1 for main; nothing reuses an id. */
        final long id;
        int state = NEW;
        /** Futex word: 1 once another thread has unparked this one. */
        final int[] permit = new int[1];
        /** Futex word: 1 once the thread has finished, for join. */
        final int[] done = new int[1];
        /** A timespec for clock_gettime and timed parks, so neither allocates. */
        final long[] time = new long[2];
        /** The monitor this thread is blocked or waiting on, and the count it holds it with. */
        Monitors.Monitor monitor;
        int monitorCount;
        /** A monitor's blocked or waiting list: a thread is on at most one. */
        Task next;

        public Task(Thread thread) {
            this.thread = thread;
            lock();
            id = ++lastId;
            unlock();
        }
    }

    private static final int MMAP = 9;
    private static final int FUTEX = 202;
    private static final int NANOSLEEP = 35;
    private static final int CLOCK_GETTIME = 228;
    private static final long PROT_READ_WRITE = 1 | 2;
    private static final long MAP_PRIVATE_ANONYMOUS_NORESERVE = 0x02 | 0x20 | 0x4000;
    private static final long FUTEX_WAIT_PRIVATE = 0 | 128;
    private static final long FUTEX_WAKE_PRIVATE = 1 | 128;
    private static final long CLOCK_MONOTONIC = 1;
    /** CLONE_VM, FS, FILES, SIGHAND, THREAD, SYSVSEM, PARENT_SETTID and CHILD_CLEARTID. */
    private static final long CLONE_FLAGS = 0x100 | 0x200 | 0x400 | 0x800 | 0x10000 | 0x40000 | 0x100000 | 0x200000;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    static final int MAX_THREADS = 256;
    /** As main's (Compiler.USER_STACK_SIZE), and HotSpot's default; touched pages only. */
    private static final long STACK_BYTES = 1024 * 1024;
    private static final long BLOCK_BYTES = 4096;
    /** Compiler.CPU_SELF, STACK_LIMIT, STACK_LIMIT_SAVED, STACK_BASE, CPU_INDEX and CPU_BLOCK_SIZE. */
    private static final int BLOCK_SELF = 0;
    private static final int BLOCK_STACK_LIMIT = 8;
    private static final int BLOCK_STACK_LIMIT_SAVED = 16;
    private static final int BLOCK_STACK_BASE = 24;
    private static final int BLOCK_INDEX = 32;
    private static final int BLOCK_TID = 128;
    private static final long MAIN_ID = 1;

    /** The lock word (0 free, 1 held, 2 held with waiters), then the holder's CPU index plus one. */
    private static final int[] LOCK = new int[2];
    private static final Task[] TASKS = new Task[MAX_THREADS];
    /** Each slot's mapping, or 0 until its first thread. */
    private static final long[] SLOTS = new long[MAX_THREADS];
    private static long mainBlock;
    private static long lastId;
    private static int threadNumber;

    private Scheduler() {
    }

    /** From UserRuntime.init, once there's a heap: main's Task. */
    static void init() {
        Task main = new Thread("main").kernelTask();
        main.state = RUNNING;
        TASKS[0] = main;
        mainBlock = Magic.cpuBlock();
        Smp.enterJava(0);
    }

    /** Takes the lock, sleeping on its word while another thread holds it. */
    static void lock() {
        long word = address(LOCK);
        if (Magic.exchangeInt(word, 1) != 0) {
            // Marked as having waiters even if this thread turns out to be the only one: a spare wake is harmless.
            while (Magic.exchangeInt(word, 2) != 0) {
                futexWait(word, 2, 0);
            }
        }
        LOCK[1] = Magic.cpuIndex() + 1;
    }

    static void unlock() {
        LOCK[1] = 0;
        long word = address(LOCK);
        if (Magic.exchangeInt(word, 0) == 2) {
            futexWake(word, 1);
        }
    }

    public static int nextThreadNumber() {
        lock();
        int number = threadNumber++;
        unlock();
        return number;
    }

    /** This thread holds the lock. */
    public static boolean lockedHere() {
        return LOCK[1] == Magic.cpuIndex() + 1;
    }

    public static Thread currentThread() {
        return current().thread;
    }

    /** Null before init. */
    static Task current() {
        return TASKS[Magic.cpuIndex()];
    }

    /** The running thread's id; main's before init, since only main runs then. */
    public static long currentId() {
        Task current = current();
        return current == null ? MAIN_ID : current.id;
    }

    public static boolean isAlive(long id) {
        lock();
        boolean alive = false;
        for (Task task : TASKS) {
            if (task != null && task.id == id && isAlive(task)) {
                alive = true;
            }
        }
        unlock();
        return alive;
    }

    public static void start(Task task) {
        lock();
        if (task.state != NEW) {
            unlock();
            throw new IllegalThreadStateException();
        }
        int slot = freeSlot();
        if (slot < 0) {
            unlock();
            throw new OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached");
        }
        long base = SLOTS[slot];
        if (base == 0) {
            base = Magic.syscall(MMAP, 0, STACK_BYTES + BLOCK_BYTES, PROT_READ_WRITE, MAP_PRIVATE_ANONYMOUS_NORESERVE, -1, 0);
            if (base < 0) {
                unlock();
                throw new OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached");
            }
            SLOTS[slot] = base;
        }
        long block = base + STACK_BYTES;
        Magic.pokeLong(block + BLOCK_SELF, block);
        // Checks off until threadMain sets its limit.
        Magic.pokeLong(block + BLOCK_STACK_LIMIT, 0);
        Magic.pokeLong(block + BLOCK_STACK_LIMIT_SAVED, 0);
        Magic.pokeLong(block + BLOCK_STACK_BASE, base);
        Magic.pokeLong(block + BLOCK_INDEX, slot);
        // The thread entry finds its block in the top word of its stack.
        long top = block - 16;
        Magic.pokeLong(top, block);
        Task previous = TASKS[slot];
        TASKS[slot] = task;
        Smp.outsideJava(slot);
        task.state = RUNNING;
        long tid = Magic.cloneThread(CLONE_FLAGS, top, block + BLOCK_TID, block + BLOCK_TID);
        if (tid < 0) {
            TASKS[slot] = previous;
            task.state = NEW;
            unlock();
            throw new OutOfMemoryError("unable to create native thread: possibly out of memory or process/resource limits reached");
        }
        unlock();
    }

    /**
     * A slot no thread is using, and whose last thread's stack is free: the kernel cleared its tid
     * word on the way out. -1 if every slot is busy. Under the lock.
     */
    private static int freeSlot() {
        for (int slot = 1; slot < MAX_THREADS; slot++) {
            Task task = TASKS[slot];
            if (task == null || task.state == DEAD && Magic.peekInt(SLOTS[slot] + STACK_BYTES + BLOCK_TID) == 0) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Where the compiler's thread entry calls a new thread, on its own stack and block. Returning
     * ends the thread: the entry calls exit.
     */
    static void threadMain() {
        Magic.resetStackLimit();
        Smp.enterJava(Magic.cpuIndex());
        Task self = current();
        try {
            self.thread.run();
        } catch (Throwable t) {
            Panic.report(self.thread.getName(), t);
        }
        lock();
        self.state = DEAD;
        self.done[0] = 1;
        unlock();
        futexWake(address(self.done), Integer.MAX_VALUE);
        Smp.outsideJava(Magic.cpuIndex());
    }

    public static boolean isAlive(Task task) {
        return task.state != NEW && task.state != DEAD;
    }

    /** The kernel schedules every thread, and preempts them itself. */
    public static void yield() {
    }

    public static void preempted() {
    }

    /** Waiting on a class initializer in another thread, which the kernel will get to. */
    public static void pause() {
        Magic.pause();
    }

    /** A blocking system call, so a collection doesn't wait for this thread to reach a safepoint. */
    public static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        Task self = current();
        self.time[0] = millis / 1000;
        self.time[1] = millis % 1000 * NANOS_PER_MILLI;
        Smp.blockingSyscall(NANOSLEEP, address(self.time), 0, 0, 0);
    }

    public static void join(Task task) {
        long word = address(task.done);
        while (task.state != NEW && task.done[0] == 0) {
            futexWait(word, 0, 0);
        }
    }

    /** Waits until every thread but this one has finished, as HotSpot does before exiting. */
    static void awaitOthers() {
        Task self = current();
        while (true) {
            Task other = null;
            lock();
            for (Task task : TASKS) {
                if (task != null && task != self && isAlive(task)) {
                    other = task;
                }
            }
            unlock();
            if (other == null) {
                return;
            }
            join(other);
        }
    }

    /**
     * Sleeps until another thread {@link #unpark}s this one, and takes the permit; false if
     * {@code deadline} (monotonic nanoseconds, 0 for none) passes first.
     */
    static boolean park(Task self, long deadline) {
        long word = address(self.permit);
        while (self.permit[0] == 0) {
            long timeout = 0;
            if (deadline != 0) {
                long left = deadline - nanoTime(self);
                if (left <= 0) {
                    return false;
                }
                self.time[0] = left / NANOS_PER_SECOND;
                self.time[1] = left % NANOS_PER_SECOND;
                timeout = address(self.time);
            }
            futexWait(word, 0, timeout);
        }
        self.permit[0] = 0;
        return true;
    }

    static void unpark(Task task) {
        task.permit[0] = 1;
        futexWake(address(task.permit), 1);
    }

    /** CLOCK_MONOTONIC, read through {@code self}'s timespec. */
    static long nanoTime(Task self) {
        Magic.syscall(CLOCK_GETTIME, CLOCK_MONOTONIC, address(self.time), 0, 0, 0, 0);
        return self.time[0] * NANOS_PER_SECOND + self.time[1];
    }

    /** The CPU block of the thread in {@code slot}. */
    static long block(int slot) {
        return slot == 0 ? mainBlock : SLOTS[slot] + STACK_BYTES;
    }

    public static int taskCount() {
        return 0;
    }

    public static long parkedFrame(int index) {
        return 0;
    }

    private static void futexWait(long word, int expected, long timeout) {
        Smp.blockingSyscall(FUTEX, word, FUTEX_WAIT_PRIVATE, expected, timeout);
    }

    private static void futexWake(long word, int count) {
        Magic.syscall(FUTEX, word, FUTEX_WAKE_PRIVATE, count, 0, 0, 0);
    }

    private static long address(int[] array) {
        return Magic.addressOf(array) + Tib.ARRAY_DATA;
    }

    private static long address(long[] array) {
        return Magic.addressOf(array) + Tib.ARRAY_DATA;
    }
}
