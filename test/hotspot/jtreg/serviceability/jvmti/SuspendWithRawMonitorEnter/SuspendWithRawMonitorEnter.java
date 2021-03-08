/*
 * Copyright (c) 2001, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 4413752
 * @summary Test SuspendThread with RawMonitor enter.
 * @requires vm.jvmti
 * @library /test/lib
 * @compile SuspendWithRawMonitorEnter.java
 * @run main/othervm/native -agentlib:SuspendWithRawMonitorEnter SuspendWithRawMonitorEnter
 */

import java.io.PrintStream;

//
// main               blocker           contender            resumer
// =================  ================  ===================  ================
// launch blocker
// <launch returns>   blocker running
// launch contender   enter threadLock
// <launch returns>   wait for notify   contender running
// launch resumer     :                 block on threadLock
// <launch returns>   :                 :                    resumer running
// suspend contender  :                 <suspended>          wait for notify
// <ready to test>    :                 :                    :
// :                  :                 :                    :
// notify blocker     wait finishes     :                    :
// notify resumer     exit threadLock   :                    wait finishes
// join blocker       :                 :                    enter threadLock
// <join returns>     blocker exits     <resumed>            resume contender
// join resumer                         :                    exit threadLock
// <join returns>                       enter threadLock     resumer exits
// join contender                       exit threadLock
// <join returns>                       contender exits
//

public class SuspendWithRawMonitorEnter {
    private static final String AGENT_LIB = "SuspendWithRawMonitorEnter";
    private static final int exit_delta   = 95;

    private static final int DEF_TIME_MAX = 60;    // default max # secs to test
    private static final int JOIN_MAX     = 30;    // max # secs to wait for join

    public static final int THR_MAIN      = 0;     // ID for main thread
    public static final int THR_BLOCKER   = 1;     // ID for blocker thread
    public static final int THR_CONTENDER = 2;     // ID for contender thread
    public static final int THR_RESUMER   = 3;     // ID for resumer thread

    public static final int TS_INIT              = 1;  // initial testState
    public static final int TS_BLOCKER_RUNNING   = 2;  // blocker is running
    public static final int TS_CONTENDER_RUNNING = 3;  // contender is running
    public static final int TS_RESUMER_RUNNING   = 4;  // resumer is running
    public static final int TS_CALL_SUSPEND      = 5;  // call suspend on contender
    public static final int TS_DONE_BLOCKING     = 6;  // done blocking threadLock
    public static final int TS_READY_TO_RESUME   = 7;  // ready to resume contender
    public static final int TS_CALL_RESUME       = 8;  // call resume on contender
    public static final int TS_CONTENDER_DONE    = 9;  // contender has run; done

    public static Object barrierLaunch = new Object();   // controls thread launch
    public static Object barrierBlocker = new Object();  // controls blocker
    public static Object barrierResumer = new Object();  // controls resumer

    public volatile static int testState;
    public static long count = 0;

    private static void log(String msg) { System.out.println(msg); }

    native static void CreateRawMonitor(int id);
    native static void DestroyRawMonitor(int id);
    native static int GetResult();
    native static void SetPrintDebug();
    native static void SuspendThread(int id, SuspendWithRawMonitorEnterWorker thr);

    public static void main(String[] args) throws Exception {
        try {
            System.loadLibrary(AGENT_LIB);
            log("Loaded library: " + AGENT_LIB);
        } catch (UnsatisfiedLinkError ule) {
            log("Failed to load library: " + AGENT_LIB);
            log("java.library.path: " + System.getProperty("java.library.path"));
            throw ule;
        }

        System.exit(run(args, System.out) + exit_delta);
    }

    public static void usage() {
        System.err.println("Usage: " + AGENT_LIB + " [-p][time_max]");
        System.err.println("where:");
        System.err.println("    -p       ::= print debug info");
        System.err.println("    time_max ::= max looping time in seconds");
        System.err.println("                 (default is " + DEF_TIME_MAX +
                           " seconds)");
        System.exit(1);
    }

    public static int run(String[] args, PrintStream out) {
        return (new SuspendWithRawMonitorEnter()).doWork(args, out);
    }

    public static void checkTestState(int exp) {
        if (testState != exp) {
            System.err.println("Failure at " + count + " loops.");
            throw new InternalError("Unexpected test state value: "
                + "expected=" + exp + " actual=" + testState);
        }
    }

    public int doWork(String[] args, PrintStream out) {
        int time_max = 0;
        if (args.length == 0) {
            time_max = DEF_TIME_MAX;
        } else {
            int arg_index = 0;
            int args_left = args.length;
            if (args[0].equals("-p")) {
                SetPrintDebug();
                arg_index = 1;
                args_left--;
            }
            if (args_left == 0) {
                time_max = DEF_TIME_MAX;
            } else if (args_left == 1) {
                try {
                    time_max = Integer.parseUnsignedInt(args[arg_index]);
                } catch (NumberFormatException nfe) {
                    System.err.println("'" + args[arg_index] +
                                       "': invalid time_max value.");
                    usage();
                }
            } else {
                usage();
            }
        }

        SuspendWithRawMonitorEnterWorker blocker;    // blocker thread
        SuspendWithRawMonitorEnterWorker contender;  // contender thread
        SuspendWithRawMonitorEnterWorker resumer;    // resumer thread

        CreateRawMonitor(THR_MAIN);

        System.out.println("About to execute for " + time_max + " seconds.");

        long start_time = System.currentTimeMillis();
        while (System.currentTimeMillis() < start_time + (time_max * 1000)) {
            count++;
            testState = TS_INIT;  // starting the test loop

            // launch the blocker thread
            synchronized (barrierLaunch) {
                blocker = new SuspendWithRawMonitorEnterWorker("blocker");
                blocker.start();

                while (testState != TS_BLOCKER_RUNNING) {
                    try {
                        barrierLaunch.wait(0);  // wait until it is running
                    } catch (InterruptedException ex) {
                    }
                }
            }

            // launch the contender thread
            synchronized (barrierLaunch) {
                contender = new SuspendWithRawMonitorEnterWorker("contender");
                contender.start();

                while (testState != TS_CONTENDER_RUNNING) {
                    try {
                        barrierLaunch.wait(0);  // wait until it is running
                    } catch (InterruptedException ex) {
                    }
                }
            }

            // launch the resumer thread
            synchronized (barrierLaunch) {
                resumer = new SuspendWithRawMonitorEnterWorker("resumer", contender);
                resumer.start();

                while (testState != TS_RESUMER_RUNNING) {
                    try {
                        barrierLaunch.wait(0);  // wait until it is running
                    } catch (InterruptedException ex) {
                    }
                }
            }

            //
            // Known bug: We don't have a way of knowing when the
            // contender thread contends on the threadLock. If we
            // suspend it before it has blocked, then we don't really
            // have contention. However, the resumer thread won't
            // resume the contender thread until after it has grabbed
            // the threadLock so we don't have a lock order problem
            // and the test won't fall over.
            //
            // We reduce the size of this timing window by launching
            // the resumer thread after the contender thread. So the
            // contender thread has all the setup time for the resumer
            // thread to call JVM/TI RawMonitorEnter() and block on
            // the threadLock.
            //
            checkTestState(TS_RESUMER_RUNNING);
            testState = TS_CALL_SUSPEND;
            SuspendThread(THR_MAIN, contender);

            //
            // At this point, all of the child threads are running
            // and we can get to meat of the test:
            //
            // - suspended threadLock contender
            // - a threadLock exit in the blocker thread
            // - a threadLock enter in the resumer thread
            // - resumption of the contender thread
            // - a threadLock enter in the freshly resumed contender thread
            //
            synchronized (barrierBlocker) {
                checkTestState(TS_CALL_SUSPEND);

                // tell blocker thread to exit threadLock
                testState = TS_DONE_BLOCKING;
                barrierBlocker.notify();
            }

            synchronized (barrierResumer) {
                // tell resumer thread to resume contender thread
                testState = TS_READY_TO_RESUME;
                barrierResumer.notify();

                // Can't call checkTestState() here because the
                // resumer thread may have already resumed the
                // contender thread.
            }

            try {
                blocker.join();
                resumer.join(JOIN_MAX * 1000);
                if (resumer.isAlive()) {
                    System.err.println("Failure at " + count + " loops.");
                    throw new InternalError("resumer thread is stuck");
                }
                contender.join(JOIN_MAX * 1000);
                if (contender.isAlive()) {
                    System.err.println("Failure at " + count + " loops.");
                    throw new InternalError("contender thread is stuck");
                }
            } catch (InterruptedException ex) {
            }

            checkTestState(TS_CONTENDER_DONE);
        }
        DestroyRawMonitor(THR_MAIN);

        System.out.println("Executed " + count + " loops in " + time_max +
                           " seconds.");

        return GetResult();
    }
}

class SuspendWithRawMonitorEnterWorker extends Thread {
    private SuspendWithRawMonitorEnterWorker target;  // target for resume operation

    public SuspendWithRawMonitorEnterWorker(String name) {
        super(name);
    }

    public SuspendWithRawMonitorEnterWorker(String name, SuspendWithRawMonitorEnterWorker target) {
        super(name);
        this.target = target;
    }

    native static int GetPrintDebug();
    native static void RawMonitorEnter(int id);
    native static void RawMonitorExit(int id);
    native static void ResumeThread(int id, SuspendWithRawMonitorEnterWorker thr);

    public void run() {
        if (GetPrintDebug() != 0) {
            System.err.println(getName() + " thread running");
        }

        //
        // Launch the blocker thread:
        // - grabs threadLock
        // - holds threadLock until we tell it let go
        // - releases threadLock
        //
        if (getName().equals("blocker")) {
            // grab threadLock before we tell main we are running
            RawMonitorEnter(SuspendWithRawMonitorEnter.THR_BLOCKER);

            SuspendWithRawMonitorEnter.checkTestState(SuspendWithRawMonitorEnter.TS_INIT);

            // recursive entry
            RawMonitorEnter(SuspendWithRawMonitorEnter.THR_BLOCKER);
            RawMonitorExit(SuspendWithRawMonitorEnter.THR_BLOCKER);

            synchronized(SuspendWithRawMonitorEnter.barrierBlocker) {
                synchronized(SuspendWithRawMonitorEnter.barrierLaunch) {
                    // tell main we are running
                    SuspendWithRawMonitorEnter.testState = SuspendWithRawMonitorEnter.TS_BLOCKER_RUNNING;
                    SuspendWithRawMonitorEnter.barrierLaunch.notify();
                }
                if (GetPrintDebug() != 0) {
                    System.err.println(getName() + " thread waiting");
                }
                // TS_READY_TO_RESUME is set right after TS_DONE_BLOCKING
                // is set so either can get the blocker thread out of
                // this wait() wrapper:
                while (SuspendWithRawMonitorEnter.testState != SuspendWithRawMonitorEnter.TS_DONE_BLOCKING &&
                       SuspendWithRawMonitorEnter.testState != SuspendWithRawMonitorEnter.TS_READY_TO_RESUME) {
                    try {
                        // wait for main to tell us when to exit threadLock
                        SuspendWithRawMonitorEnter.barrierBlocker.wait(0);
                    } catch (InterruptedException ex) {
                    }
                }
                RawMonitorExit(SuspendWithRawMonitorEnter.THR_BLOCKER);
            }
        }
        //
        // Launch the contender thread:
        // - tries to grab the threadLock
        // - grabs threadLock
        // - releases threadLock
        //
        else if (getName().equals("contender")) {
            synchronized(SuspendWithRawMonitorEnter.barrierLaunch) {
                // tell main we are running
                SuspendWithRawMonitorEnter.testState = SuspendWithRawMonitorEnter.TS_CONTENDER_RUNNING;
                SuspendWithRawMonitorEnter.barrierLaunch.notify();
            }

            RawMonitorEnter(SuspendWithRawMonitorEnter.THR_CONTENDER);

            SuspendWithRawMonitorEnter.checkTestState(SuspendWithRawMonitorEnter.TS_CALL_RESUME);
            SuspendWithRawMonitorEnter.testState = SuspendWithRawMonitorEnter.TS_CONTENDER_DONE;

            RawMonitorExit(SuspendWithRawMonitorEnter.THR_CONTENDER);
        }
        //
        // Launch the resumer thread:
        // - tries to grab the threadLock (should not block!)
        // - grabs threadLock
        // - resumes the contended thread
        // - releases threadLock
        //
        else if (getName().equals("resumer")) {
            synchronized(SuspendWithRawMonitorEnter.barrierResumer) {
                synchronized(SuspendWithRawMonitorEnter.barrierLaunch) {
                    // tell main we are running
                    SuspendWithRawMonitorEnter.testState = SuspendWithRawMonitorEnter.TS_RESUMER_RUNNING;
                    SuspendWithRawMonitorEnter.barrierLaunch.notify();
                }
                if (GetPrintDebug() != 0) {
                    System.err.println(getName() + " thread waiting");
                }
                while (SuspendWithRawMonitorEnter.testState != SuspendWithRawMonitorEnter.TS_READY_TO_RESUME) {
                    try {
                        // wait for main to tell us when to continue
                        SuspendWithRawMonitorEnter.barrierResumer.wait(0);
                    } catch (InterruptedException ex) {
                    }
                }
            }
            RawMonitorEnter(SuspendWithRawMonitorEnter.THR_RESUMER);

            SuspendWithRawMonitorEnter.checkTestState(SuspendWithRawMonitorEnter.TS_READY_TO_RESUME);
            SuspendWithRawMonitorEnter.testState = SuspendWithRawMonitorEnter.TS_CALL_RESUME;

            // resume the contender thread so contender.join() can work
            ResumeThread(SuspendWithRawMonitorEnter.THR_RESUMER, target);

            RawMonitorExit(SuspendWithRawMonitorEnter.THR_RESUMER);
        }
    }
}
