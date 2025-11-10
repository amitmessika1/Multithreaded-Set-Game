package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    //check if the dealer is busy
    public volatile boolean isBusy;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    //potential set - each cell represent slot
    public BlockingQueue<Integer> sets;

    private long dealerTimer;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        sets = new ArrayBlockingQueue<>(env.config.players, true);
        this.dealerTimer = Math.min(950, env.config.turnTimeoutMillis);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        for(Player player: players)
        {
            Thread playerThread = new Thread(player, "player");
            playerThread.start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            isBusy = false;
            updateTimerDisplay(true);
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        try { Thread.sleep(env.config.endGamePauseMillies); }
        catch(InterruptedException ex) {}
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
        }
    }

    /**
     * Called when the game should be terminated.
     */
        public void terminate() {
        terminate = true;
        for (Player player : players) {
            player.terminate();//interrupt the playerThread
        }
    }


    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        synchronized (sets) {
            while (!sets.isEmpty()) {
                int playerID = sets.poll(); //the first player who find a potential set
                Player currPlayer = players[playerID];
                int [] setSlots = new int[currPlayer.playerTokens.size()];  //casting the token list to an array
                for(int i=0; i<setSlots.length; i++){
                    setSlots[i] = currPlayer.playerTokens.get(i);
                }
                if (setSlots.length == 3) {
                    if (checkSet(setSlots)) { //checking if it is a legal set
                        currPlayer.waitingTime = 1; //point
                        synchronized (table) {
                            for (int i = 0; i < setSlots.length; i++) {  //for each slot - removing all the token that placed on it
                                for (Player player : players) {
                                    if (player.hasToken(setSlots[i]) != -1)
                                        player.playerTokens.remove((Object) setSlots[i]); //update in each player their token list
                                }
                                table.removeCard(setSlots[i]); //delete the card
                            }
                        }
                        updateTimerDisplay(true);
                        placeCardsOnTable();
                    }
                    else { //penalty - illegal set
                        currPlayer.waitingTime = 2;
                    }
                }
                synchronized (currPlayer.lock) {
                    currPlayer.lock.notifyAll();
                }
            }
        }
    }

    public boolean checkSet(int[] setSlots){
        int [] setCards = new int [setSlots.length]; //casting the set that contain slots to cards
        for(int i=0; i<setSlots.length; i++){
            setCards[i] = table.slotToCard[setSlots[i]];
        }
        boolean legalSet = env.util.testSet(setCards); //checking if the set is legal
        return legalSet;
    }


    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        synchronized(table){
            if(!deck.isEmpty()){
                Collections.shuffle(deck);  //shuffle the deck
                for(int i=0 ; i<table.slotToCard.length; i++){
                    if (!table.thereIsCard(i)){ // every place that we need to place card
                        table.placeCard(deck.get(0), i);
                        deck.remove(0);
                    }
                }
            }
            table.notifyAll();
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        long time = System.currentTimeMillis();
        while(System.currentTimeMillis() < time + dealerTimer){
            try{
                synchronized(sets){
                    boolean flag = false;
                    while(sets.size() == 0 && !flag){
                        sets.wait(Math.max(dealerTimer - (System.currentTimeMillis() - time),1));
                        flag = true;
                    }
                    if(sets.size() > 0){
                       removeCardsFromTable();
                    }
                }
            } catch(InterruptedException ignored){}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            this.reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            dealerTimer = Math.min(950, env.config.turnTimeoutMillis);
        }
        else if(reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis){
            env.ui.setCountdown(Math.max(reshuffleTime - System.currentTimeMillis(),0), true);
            dealerTimer = 1;
        }
        else{
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        isBusy = true;
        synchronized(table){
            for(int i=0; i<table.slotToCard.length; i++){
                if(table.thereIsCard(i)){
                    deck.add(table.slotToCard[i]);
                    for(int j=0; j<players.length; j++){
                        if(players[j].hasToken(i) != -1){
                            table.removeToken(players[j].id, i);
                            players[j].playerTokens.clear();
                        }
                    }
                    table.removeCard(i);
                }
            }
            table.notifyAll();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = 0;
        int length = 0;

        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > maxScore) {  //find the highest score
                maxScore = players[i].score();
                length = 1;
            } else if (players[i].score() == maxScore) { //update the number of players with the maxScore
                length++;
            }
        }

        int[] Winners = new int[length];
        int j = 0;
        for (int i = 0; i < players.length; i++) {  //create int[] with the id of the winners
            if (players[i].score() == maxScore) {
                Winners[j] = players[i].id;
                j++;
            }
        }
        env.ui.announceWinner(Winners); //display the winners
    }
}
