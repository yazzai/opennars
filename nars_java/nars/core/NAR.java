package nars.core;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;

import nars.entity.Stamp;
import nars.gui.NARWindow;
import nars.io.InputChannel;
import nars.io.Output;
import nars.storage.Memory;

/**
 * Non-Axiomatic Reasoner (core class)
 * <p>
 * An instantation of a NARS logic processor, useful for batch functionality; 
*/
public class NAR implements Runnable, Output {

    
    private Thread thread = null;
    long minTickPeriodMS;
    
    /**
     * global DEBUG print switch
     */
    public static boolean DEBUG = false;
    /**
     * The name of the reasoner
     */
    protected String name;
    /**
     * The memory of the reasoner
     */
    public final Memory memory;
    /**
     * The input channels of the reasoner
     */
    protected List<InputChannel> inputChannels;
    private List<InputChannel> closedInputChannels;

    /**
     * The output channels of the reasoner
     */
    protected List<Output> outputChannels;
    /**
     * System clock, relatively defined to guarantee the repeatability of
     * behaviors
     */
    private long clock;
    /**
     * Flag for running continuously
     */
    private boolean running = false;
    /**
     * The remaining number of steps to be carried out (walk mode)
     */
    private int walkingSteps;
    /**
     * determines the end of {@link NARSBatch} program (set but not accessed in
     * this class)
     */
    private boolean finishedInputs;
    /**
     * System clock - number of cycles since last output
     */
    private long timer;
    private AtomicInteger silenceValue = new AtomicInteger(Parameters.SILENT_LEVEL);
    private boolean paused;

    /**arbitrary data associated with this particular NAR instance can be stored here */
    public final HashMap data = new HashMap();

    
    public NAR() {
        memory = new Memory(this);
        
        //needs to be concurrent in case NARS makes changes to the channels while running
        inputChannels = new CopyOnWriteArrayList<>();
        outputChannels = new CopyOnWriteArrayList<>();
        closedInputChannels = new CopyOnWriteArrayList<>();
    }

    /**
     * Reset the system with an empty memory and reset clock. Called locally and
     * from {@link NARWindow}.
     */
    final String resetMessage = " OUT: reset";

    public void setSilenceValue(int s) {
        this.silenceValue.set(s);
    }
    
    
    public void reset() {
            
        walkingSteps = 0;
        clock = 0;
        memory.init();
        Stamp.init();
        
        output(OUT.class, resetMessage);
                
    }

    public Memory getMemory() {
        return memory;
    }

    public void addInputChannel(InputChannel channel) {
        inputChannels.add(channel);
    }

    public void removeInputChannel(InputChannel channel) {
        inputChannels.remove(channel);
    }

    public void addOutputChannel(Output channel) {
        outputChannels.add(channel);
    }

    public void removeOutputChannel(Output channel) {
        outputChannels.remove(channel);
    }

    /**
     * Get the current time from the clock Called in {@link nars.entity.Stamp}
     *
     * @return The current time
     */
    public long getTime() {
        return clock;
    }


    /**
     * Will carry the inference process for a certain number of steps
     *
     * @param n The number of inference steps to be carried
     */
    public void walk(int n) {
        if (DEBUG)
            output(OUT.class, "thinking " + n + (n > 1 ? " cycles" : " cycle"));
        walkingSteps = n;
    }
    
    public void walk(final int steps, final boolean immediate) {
        if (immediate) {
            stop();
            paused = false;
            running = true;
            tick();
        }
        
        walk(steps);
        
        if (immediate) {
            running = false;
        }
    }
    
    /**
     * Repeatedly execute NARS working cycle. This method is called when the
     * Runnable's thread is started.
     */    
    public void start(long minTickPeriodMS) {
        if (thread == null) {
            thread = new Thread(this, "Inference");
            thread.start();
        }        
        this.minTickPeriodMS = minTickPeriodMS;
        running = true;
        paused = false;
    }
    
    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }
    
    /**
     * Will stop the inference process
     */
    public void stop() {
        if (thread!=null) {
            thread.interrupt();
            thread = null;
        }
        running = false;
    }    
    
    public void run(int minCycles) {
        run(minCycles, false);
    }
    
    public void run(int minCycles, boolean debug) {
        DEBUG = debug; 
        running = true;
        paused = false;
        tick();
        for (int i = 0; i < minCycles-1; i++)
            tick();
        for (int i = 0; i < walkingSteps; i++)
            tick();
        running = false;
        paused = true;
    }
    
    @Override public void run() {
        
        while (running) {            
            try {
                tick();
            } catch (RuntimeException re) {                
                output(ERR.class, re + " " + Arrays.asList(re.getStackTrace()));
                if (DEBUG) {
                    System.err.println(re);                
                    re.printStackTrace();
                }
            }
            catch (Exception e) {
                System.err.println(e);
                e.printStackTrace();
            }
            
            if (minTickPeriodMS > 0) {
                try {
                    Thread.sleep(minTickPeriodMS);
                } catch (InterruptedException e) {            }
                Thread.yield();
            }
        }
    }
    
    

    /**
     * A clock tick. Run one working workCycle or read input. Called from NARS
     * only.
     */
    public void tick() {
        if (DEBUG) {
            if (running || walkingSteps > 0 || !finishedInputs) {
                System.out.println("// doTick: "
                        + "walkingSteps " + walkingSteps
                        + ", clock " + clock
                        + ", getTimer " + getSystemClock());

                System.out.flush();
            }
        }
        
        if (walkingSteps == 0) {
            boolean reasonerShouldRun = false;



            for (final InputChannel channelIn : inputChannels) {
                if (DEBUG) {
                    System.out.println("Input: " + channelIn);
                }

                if (!channelIn.isClosed()) {
                    boolean b = channelIn.nextInput();  
                    if (b)
                        reasonerShouldRun = true;
                }
                else                
                    closedInputChannels.add(channelIn);
            }
            finishedInputs = !reasonerShouldRun;

            for (final InputChannel c : closedInputChannels) {
                inputChannels.remove(c);
            }
            closedInputChannels.clear();
        
        }
                


        if (((running || walkingSteps > 0)) && (!paused)) {
            clock++;
            tickTimer();
            try {
                memory.workCycle(clock);
            }
            catch (RuntimeException e) {
                output(ERR.class, e.toString());
                if (DEBUG)
                    e.printStackTrace();
            }
            if (walkingSteps > 0) {
                walkingSteps--;
            }
        }

                
    }
    
    @Override
    public void output(final Class channel, final Object o) {       
        for (final Output channelOut : outputChannels)
            channelOut.output(channel, o);
    }


    /**
     * determines the end of {@link NARSBatch} program
     */
    public boolean isFinishedInputs() {
        return finishedInputs;
    }


    @Override
    public String toString() {
        return memory.toString();
    }

    /**
     * Report Silence Level
     */
    public AtomicInteger getSilenceValue() {
        return silenceValue;
    }

    /**
     * To get the timer value and then to
     * reset it by {@link #initTimer()};
     * plays the same role as {@link nars.gui.MainWindow#updateTimer()} 
     *
     * @return The previous timer value
     */
    public long updateTimer() {
        long i = getSystemClock();
        initTimer();
        return i;
    }

    /**
     * Reset timer;
     * plays the same role as {@link nars.gui.MainWindow#initTimer()} 
     */
    public void initTimer() {
        setTimer(0);
    }

    /**
     * Update timer
     */
    public void tickTimer() {
        setTimer(getSystemClock() + 1);
    }

    /** @return System clock : number of cycles since last output */
    public long getSystemClock() {
        return timer;
    }

    /** set System clock : number of cycles since last output */
    private void setTimer(long timer) {
        this.timer = timer;
    }

    public boolean isRunning() {
        return running;
    }    

    public boolean isPaused() {
        return paused;
    }

    public long getMinTickPeriodMS() {
        return minTickPeriodMS;
    }

    
}