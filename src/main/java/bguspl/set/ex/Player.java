package bguspl.set.ex;
import bguspl.set.Env;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.Random;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    // The actions queue
    private BlockingQueue<Integer> actionsQueue;

    //data structure which save the player's tokens - each link represent slot
    public List <Integer> playerTokens;


    public final Object lock = new Object();

    /**to seperate between the diffrent waiting times
     * 0 - wait for the dealer
     * 1- point freeze
     * 2- penalty freeze
     */
    public volatile int waitingTime;


    //check if frozen
    public volatile boolean isFrozen;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        actionsQueue = new ArrayBlockingQueue<>(3, true);
        this.dealer = dealer;
        playerTokens = new LinkedList<>();
        waitingTime = 0;
        isFrozen = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            try {
                if (!actionsQueue.isEmpty()) {  //the player had pressed key to make an action
                    int slot = actionsQueue.poll(); //the first action
                    if (hasToken(slot)==-1) { //the player doesn't have a token on this slot
                        table.semaphore.acquire();
                        synchronized (dealer.sets) {
                            synchronized (table) {
                                if (table.thereIsCard(slot) && playerTokens.size() < 3) { //check if it possible to place this token
                                    table.placeToken(id, slot);
                                    playerTokens.add(slot);
                                    if (playerTokens.size() == 3) { //the player have a potential set
                                        isFrozen = true;
                                        dealer.sets.add(id);
                                        dealer.sets.notifyAll();
                                    }
                                }
                            }
                        }
                        table.semaphore.release();

                        if (isFrozen) {
                            synchronized (lock) {
                                while (waitingTime == 0) { //wait for the dealer finished his job-checks the set
                                    try {
                                        lock.wait();
                                    }
                                    catch (InterruptedException ignored) {}
                                }
                            }
                            if (waitingTime == 1) { //legal set
                                point();
                            }
                            else if (waitingTime == 2) { //illegal set
                                penalty();
                            }
                        }
                    }
                    else { //the player has token on this slot - remove this token
                        playerTokens.remove((Object)slot);
                        table.removeToken(id,slot);
                    }
                }
            }
            catch(InterruptedException ignored) {}
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Random slot = new Random();
                int randSlot = (int) slot.nextInt(12); //random key that the computer player press
                synchronized (actionsQueue)
                {
                    if(actionsQueue.remainingCapacity() != 0)
                    {
                        keyPressed(randSlot);
                    }
                }
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate=true;
        playerThread.interrupt();
        if(!human) {
            aiThread.interrupt();
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if(table.thereIsCard(slot) & available()){ //the player and the dealer can make this action
            synchronized(actionsQueue){
                try {
                    actionsQueue.add(slot);
                    actionsQueue.notifyAll();
                }
                catch (IllegalStateException ignored) {}
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        score++;
        env.ui.setScore(id, score);

        try{
            env.ui.setFreeze(id, 1000); //present the time that left to the end of the freezing in the panel
            Thread.sleep(1000);
            env.ui.setFreeze(id, 0);
        }
        catch (InterruptedException exception) {}
        isFrozen = false;
        waitingTime = 0;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try{
            for(long i = (env.config.penaltyFreezeMillis/1000) ; i > 0 ; i--){  //for every second in the freezing
                env.ui.setFreeze(id, i*1000); //present the time that left to the end of the freezing in the panel
                Thread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        }
        catch (InterruptedException exception) {}
        isFrozen = false;
        waitingTime = 0;
    }

    public int score() {
        return score;
    }

    public int hasToken(int slot){
        for(int i=0; i<playerTokens.size(); i++){
            if (playerTokens.get(i) == slot & table.thereIsCard(slot)) //check if the slot similar to the token slot
                return i;
        }
        return -1;
    }

    public boolean available(){
        if (!isFrozen & !dealer.isBusy) return true;
        return false;
    }
}

