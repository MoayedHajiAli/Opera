package Underlay;

import Metrics.SimulatorCounter;
import Metrics.SimulatorHistogram;
import Node.BaseNode;
import Simulator.Simulator;
import SimulatorEvents.StopStartEvent;
import java.util.AbstractMap.SimpleEntry;
import Underlay.packets.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import Utils.SimulatorUtils;
import org.apache.log4j.Logger;
/**
 * Represents a mediator between the overlay and the underlay. The requests coming from the underlay are directed
 * to the overlay and the responses emitted by the overlay are returned to the underlay. The requests coming from
 * the overlay are either directed to the underlay or to another local overlay, and the emitted response is returned
 * to the overlay.
 */
public class MiddleLayer {
    //TODO add bucket size to the default metrics
    private final String SENT_BUCKET_SIZE_METRIC = "SentBucketSize";
    private final String RECEIVED_BUCKET_SIZE_METRIC = "ReceivedBucketSize";

    private final String DELAY_METRIC = "Delay";
    private final String SENT_MSG_CNT_METRIC = "Sent_Messages";
    private final String RECEIVED_MSG_CNT_METRIC = "Received_Messages";
    private static final Logger log = Logger.getLogger(MiddleLayer.class.getName());

    private Underlay underlay;
    private BaseNode overlay;
    private HashMap<UUID, SimpleEntry<String, Integer>> allFUllAddresses;
    private HashMap<SimpleEntry<String, Integer>, Boolean> isReady;
    private UUID nodeID;

    // TODO : make the communication between the nodes and the simulator (the master node) through the network
    private Simulator masterNode;

    private String sentBucketHash(UUID id){
        return  SimulatorUtils.hashPairOfNodes(nodeID, id);
    }
    private String receivedBucketHash(UUID id){
        return  SimulatorUtils.hashPairOfNodes(id, nodeID);
    }

    public void setUnderlay(Underlay underlay){
        this.underlay = underlay;
    }

    public void setOverlay(BaseNode overlay){
        this.overlay = overlay;
    }

    public Underlay getUnderlay() {
        return underlay;
    }

    public BaseNode getOverlay() {
        return overlay;
    }

    public  MiddleLayer(UUID nodeID, HashMap<UUID, SimpleEntry<String, Integer>> allFUllAddresses, HashMap<SimpleEntry<String, Integer>, Boolean> isReady, Simulator masterNode) {
        //register metrics
        SimulatorHistogram.register(DELAY_METRIC);
        SimulatorCounter.register(SENT_MSG_CNT_METRIC);
        SimulatorCounter.register(RECEIVED_MSG_CNT_METRIC);

        this.nodeID = nodeID;
        this.allFUllAddresses = allFUllAddresses;
        this.isReady = isReady;
        this.masterNode = masterNode;
    }

    /**
     * Called by the overlay to send requests to the underlay.
     * @param destinationID destenation node unique id.
     * @param event the event.
     * @return true if event was sent successfully. false, otherwise.
     */
    public boolean send(UUID destinationID, Event event) {
        // check the readiness of the destination node
        SimpleEntry<String, Integer> fullAddress = allFUllAddresses.get(destinationID);
        if(!isReady.get(fullAddress)){
            log.debug("[LocalUnderlay] " + fullAddress + ": Node is not ready");
            return false;
        }
        // update metrics
        SimulatorCounter.inc(SENT_MSG_CNT_METRIC, nodeID);
        SimulatorHistogram.startTimer(DELAY_METRIC, nodeID, sentBucketHash(destinationID));

        // wrap the event by request class
        Request request = new Request(event, this.nodeID, destinationID);
        String destinationAddress = fullAddress.getKey();
        Integer port = fullAddress.getValue();

        // sleep for the simulated duration
        try{
            Thread.sleep(masterNode.getSimulatedLatency(nodeID, destinationID, true));
        }catch (Exception e){
            Simulator.getLogger().error("[MiddleLayer] Thread failed to sleep for the simulated delay");
        }

        // Bounce the request up.
        boolean success =  underlay.sendMessage(destinationAddress, port, request);

        // logging
        if(success)
            log.info("[MiddleLayer] " + this.getAddress(nodeID) + " : node sent an event " + event.logMessage());
        else
            log.debug("[MiddleLayer] " + this.getAddress(nodeID) + " : node could not send an event " + event.logMessage());

        return success;
    }

    /**
     * Called by the underlay to collect the response from the overlay.
     */
    public void receive(Request request) {
        // check the readiness of the overlay
        SimpleEntry<String, Integer> fullAddress = allFUllAddresses.get(nodeID);
        if(!isReady.get(fullAddress)){
            log.debug("[LocalUnderlay] " + fullAddress + ": Node is not ready");
            return;
        }
        // update metrics
        SimulatorCounter.inc(RECEIVED_MSG_CNT_METRIC, nodeID);
        SimulatorHistogram.observeDuration(DELAY_METRIC, receivedBucketHash(request.getOrginalID()));

        // logging
        log.info("[MiddleLayer] " + this.getAddress(nodeID) + " : node received an event " + request.getEvent().logMessage());

        // check if the event is start, stop event and handle it directly
        if (request.getEvent() instanceof StopStartEvent){
            StopStartEvent event = (StopStartEvent) request.getEvent();
            if(event.getState())
                this.start();
            else
                this.stop(event.getAddress(), event.getPort());
        }
        else overlay.onNewMessage(request.getOrginalID(), request.getEvent());
    }

    /**
     * Terminates the node in a new thread.
     */
    public void stop(String address, int port)
    {
        new Thread(){
            @Override
            public void run() {
                overlay.onStop();
                boolean success = underlay.terminate(address, port);
                if(success)
                    log.info("[MiddleLayer] node " + getAddress(nodeID) + " is terminating");
                else
                    log.error("[MiddleLayer] node " + getAddress(nodeID) + " could not be terminated");
            }
        }.start();
    }

    /**
     * start the node in a new thread.
     * This method will be called once the simulator send a start event to the node
     */
    public void start()
    {
        log.info("[MiddleLayer] node " + getAddress(nodeID) + " is starting");
        new Thread(){
            @Override
            public void run() {
                overlay.onStart();
            }
        }.start();
    }

    /**
     * declare the node as Ready (called by the overlay)
     */
    public void ready(){
        //logging
        log.info("[MiddleLayer] node " + getAddress(nodeID) + " is ready");

        masterNode.Ready(this.nodeID);
    }

    /**
     * request node termination (called by the overlay)
     */
    public void done(){
        //logging
        log.info("[MiddleLayer] node " + getAddress(nodeID) + " requests termination");

        masterNode.Done(this.nodeID);
    }

    public void initUnderLay(){
        //logging
        log.info("[MiddleLayer] initializing the underlay for node " + getAddress(nodeID));

        int port = this.allFUllAddresses.get(this.nodeID).getValue();
        this.underlay.initUnderlay(port);
    }

    /**
     * Call the node onCreat on a new thread
     * @param allID
     */
    public void create(ArrayList<UUID> allID) {
        log.info("[MiddleLayer] creating node " + getAddress(nodeID));
        new Thread(){
            @Override
            public void run() {
                overlay.onCreate(allID);
            }
        }.start();
    }

    public String getAddress(UUID nodeID){
        SimpleEntry<String, Integer> address = allFUllAddresses.get(nodeID);
        return address.getKey() + ":" + address.getValue();
    }
}
