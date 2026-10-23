package duke.user;

/**
 * Thread.sleep: output ordering only, including a collection while another thread sleeps.
 *
 * <p>The 6 s sleep is intentional: if sleep used a plain Magic.syscall instead of
 * Smp.blockingSyscall the GC's stopOthers would time out (5 s limit) and panic before the
 * sleeper ever reached a safepoint, catching that regression.
 */
class Sleep {

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /** Set by the asleep thread; read by main after join so the output order is deterministic. */
    private static volatile boolean asleepWoke;

    static void main() throws InterruptedException {
        Thread sleeper = new Thread(() -> {
            sleep(50);
            System.out.println("sleeper woke");
        });
        sleeper.start();
        System.out.println("main before join");
        sleeper.join();
        System.out.println("joined, sleeper alive " + sleeper.isAlive());

        sleep(0);
        System.out.println("slept 0");

        Thread asleep = new Thread(() -> {
            sleep(6_000);
            asleepWoke = true;
        });
        asleep.start();
        long total = 0;
        for (int i = 0; i < 2_000; i++) {
            total += new int[16 * 1024].length;
        }
        System.out.println("allocated " + total);
        asleep.join();
        System.out.println("asleep woke " + asleepWoke);
        System.out.println("done");
    }
}
