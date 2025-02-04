package pedSim.routeChoice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.javatuples.Pair;

import pedSim.agents.Agent;
import pedSim.cognitiveMap.Gateway;
import pedSim.cognitiveMap.Region;
import pedSim.engine.PedSimCity;
import sim.graph.EdgeGraph;
import sim.graph.GraphUtils;
import sim.graph.NodeGraph;
import sim.util.geo.Angles;
import sim.util.geo.Utilities;

/**
 * Series of functions for computing a sequence of region-gateways between the
 * space generated by an origin and a destination. This class generates
 * therefore the so-called navigational coarse plan.
 */
public class RegionBasedNavigation {

	List<Integer> visitedRegions = new ArrayList<>();
	List<NodeGraph> gatewaySequence = new ArrayList<>();
	List<Pair<NodeGraph, NodeGraph>> badExits = new ArrayList<>();
	List<NodeGraph> gatewaysToIgnore = new ArrayList<>();

	NodeGraph originNode, destinationNode, currentNode, previousNode;
	int currentRegionID, specificRegionID, targetRegionID;
	boolean finalRegion = false;
	Map<Integer, EdgeGraph> edgesMap;
	Map<Pair<NodeGraph, NodeGraph>, Gateway> gatewaysMap = new HashMap<Pair<NodeGraph, NodeGraph>, Gateway>();
	Map<Pair<NodeGraph, NodeGraph>, Double> validGateways;
	Map<Pair<NodeGraph, NodeGraph>, Double> otherGateways;

	private Agent agent;
	private Map<Integer, Region> regionsMap;

	/**
	 * Constructs a RegionBasedNavigation object.
	 *
	 * @param originNode      The origin node.
	 * @param destinationNode The destination node.
	 * @param agent           The agent for which region-based navigation is being
	 *                        computed.
	 */
	public RegionBasedNavigation(NodeGraph originNode, NodeGraph destinationNode, Agent agent) {
		this.originNode = originNode;
		this.destinationNode = destinationNode;
		this.agent = agent;
		this.regionsMap = new HashMap<>(PedSimCity.regionsMap);
	}

	/**
	 * Returns a sequence of nodes representing the traversed regions, entry and
	 * exit gateways between the origin and the destination.
	 *
	 * If the agent also uses barriers, barrier sub-goals are also identified when
	 * applicable.
	 *
	 * @return The sequence of nodes representing the planned navigation.
	 * @throws Exception
	 */
	public List<NodeGraph> sequenceRegions() throws Exception {
		initializeSequence(); // Extracted initialization logic
		while (!finalRegion) {
			nextGateways(); // Extracted logic for handling next gateways
			if (gatewaySequence.contains(destinationNode) && !finalRegion)
				return gatewaySequence;
		}

		// clear the sets
		visitedRegions.clear();
		badExits.clear();
		// sub-goals and barriers - navigation
		List<NodeGraph> sequence = new ArrayList<>();
		// if also barrier navigation, insert barrier-sub goals into the sequence
		if (agent.getProperties().barrierBasedNavigation) {
			sequence = regionalBarriers();
		} else
			sequence = gatewaySequence;

		// remove duplicates and maintains order
		Set<NodeGraph> ns = new LinkedHashSet<>(sequence);
		sequence = new ArrayList<>(ns);
		return sequence;
	}

	/**
	 * Initialises the sequence of nodes and regions for navigation.
	 */
	private void initializeSequence() {
		this.edgesMap = PedSimCity.edgesMap;
		this.gatewaysMap = PedSimCity.gatewaysMap;
		currentNode = originNode;
		currentRegionID = originNode.getRegionID();
		targetRegionID = destinationNode.getRegionID();
		visitedRegions.add(currentRegionID);
		previousNode = null;
		gatewaySequence.add(originNode);
		if (currentRegionID == targetRegionID) {
			finalRegion = true;
			gatewaySequence.add(destinationNode);
		}
	}

	/**
	 * Finds the next gateways. This method calculates the next gateways based on
	 * the current node and region, updates the sequence of gateways, and maintains
	 * necessary tracking information for the navigation process.
	 */
	private void nextGateways() {
		final Pair<NodeGraph, NodeGraph> gateways = findNextGateway(currentNode, currentRegionID, -1);
		if (gateways == null) {
			if (previousNode != null)
				handleInvalidGateway();
			else
				gatewaySequence.add(destinationNode);
			return;
		} else {
			previousNode = currentNode;
			gatewaySequence.add(gateways.getValue0());
			gatewaySequence.add(gateways.getValue1());
			currentNode = gatewaySequence.get(gatewaySequence.size() - 1);
			currentRegionID = currentNode.getRegionID();
			visitedRegions.add(currentRegionID);

			if (currentRegionID == targetRegionID) {
				finalRegion = true;
				gatewaySequence.add(destinationNode);
				return;
			}
		}
	}

	/**
	 * Handles an invalid gateway encountered during navigation. This method
	 * identifies the invalid gateway pair, adds it to the list of bad exits to
	 * avoid it in future calculations, reverts the navigation state to the previous
	 * node, updates tracking information, and resets the gateway sequence.
	 */
	private void handleInvalidGateway() {

		final Pair<NodeGraph, NodeGraph> badPair = new Pair<>(gatewaySequence.get(gatewaySequence.size() - 2),
				gatewaySequence.get(gatewaySequence.size() - 1));
		// add last exit to the ones to avoid in feature
		badExits.add(badPair);
		currentNode = previousNode;
		currentRegionID = previousNode.getRegionID();
		gatewaySequence = new ArrayList<>(gatewaySequence.subList(0, gatewaySequence.size() - 2));
		visitedRegions.remove(visitedRegions.size() - 1);
		previousNode = null;
	}

	/**
	 * Identifies the next gateway (exit and entry nodes) towards the best region
	 * for the current location and region.
	 *
	 * @param currentNode      The current node.
	 * @param currentRegion    The current region.
	 * @param specificRegionID A desired region (optional).
	 * @return The pair of gateways representing the next step in navigation.
	 */
	private Pair<NodeGraph, NodeGraph> findNextGateway(NodeGraph currentNode, int currentRegion, int specificRegionID) {

		// retrieve current region's exits
		List<Gateway> possibleGateways = regionsMap.get(currentRegion).gateways;
		validGateways = new ConcurrentHashMap<>();
		otherGateways = new ConcurrentHashMap<>();

		// check compliance with criteria
		double destinationAngle = Angles.angle(currentNode, destinationNode);
		double distanceTarget = GraphUtils.nodesDistance(currentNode, destinationNode);

		possibleGateways.parallelStream().forEach(gateway -> {
			if (isGatewayValid(gateway, specificRegionID)) {
				evaluateGateway(gateway, destinationAngle, distanceTarget, currentNode);
			}
		});

		if (validGateways.isEmpty() && specificRegionID != -1)
			return null;
		if (validGateways.isEmpty())
			validGateways = otherGateways;
		if (validGateways.isEmpty())
			return null;

		// sort the valid gates, rewarding the ones with the lowest deviation towards
		// the destination
		Map<Pair<NodeGraph, NodeGraph>, Double> validSorted = (LinkedHashMap<Pair<NodeGraph, NodeGraph>, Double>) Utilities
				.sortByValue(validGateways, false);

		// return the first gateway pair
		for (Entry<Pair<NodeGraph, NodeGraph>, Double> gatewaysPair : validSorted.entrySet())
			return gatewaysPair.getKey();
		return null;
	}

	/**
	 * Checks the validity of a gateway.
	 * 
	 * @param gateway          The gateway to be checked for validity.
	 * @param specificRegionID The specific region ID to which the gateway should
	 *                         belong, or -1 if any region is acceptable.
	 * @return True if the gateway is valid based on the specified conditions,
	 *         otherwise false.
	 */
	private boolean isGatewayValid(Gateway gateway, int specificRegionID) {
		if (badExits.contains(gateway.gatewayID))
			return false;
		if (specificRegionID != -1 && specificRegionID != gateway.regionTo)
			return false;
		if (visitedRegions.contains(gateway.regionTo))
			return false;
		else
			return true;
	}

	/**
	 * Evaluates the given gateway based on the criteria.
	 *
	 * @param gateway          The gateway being evaluated.
	 * @param destinationAngle The angle towards the destination.
	 * @param distanceTarget   The distance to the target.
	 * @param currentNode      The current node in the navigation process.
	 */
	private void evaluateGateway(Gateway gateway, double destinationAngle, double distanceTarget,
			NodeGraph currentNode) {

		double locationExitAngle = Angles.angle(currentNode, gateway.exit);
		double exitEntryAngle = gateway.entryAngle;
		double exitDestintionAngle = Angles.angle(gateway.exit, destinationNode);
		double differenceExitEntry = Angles.differenceAngles(locationExitAngle, exitDestintionAngle);
		double distanceFromGate = GraphUtils.nodesDistance(currentNode, gateway.exit);

		double cost = 0.0;
		boolean entryInDirection = Angles.isInDirection(destinationAngle, exitEntryAngle, 140.0);
		boolean exitInDirection = Angles.isInDirection(destinationAngle, locationExitAngle, 140.0);
		boolean notInDirection = (distanceFromGate > distanceTarget || !exitInDirection || !entryInDirection);
		// criteria are not met
		if (isCurrentExit(gateway) && !entryInDirection) {
			cost = Angles.differenceAngles(exitEntryAngle, destinationAngle);
			otherGateways.put(gateway.gatewayID, cost);
		} else if (!isCurrentExit(gateway) && notInDirection)
			addInvalidGateway(gateway, locationExitAngle, destinationAngle, differenceExitEntry);
		else
			addValidGateway(gateway, locationExitAngle, destinationAngle, differenceExitEntry);

	}

	/**
	 * Checks if the given gateway's exit matches the current node in the
	 * navigation.
	 *
	 * @param gateway The gateway to be checked.
	 * @return True if the gateway's exit matches the current node, false otherwise.
	 */
	private boolean isCurrentExit(Gateway gateway) {
		return gateway.exit.equals(currentNode);
	}

	/**
	 * Adds the given gateway to the 'otherGateways' collection based on certain
	 * criteria.
	 *
	 * @param gateway             The gateway to be added.
	 * @param locationExitAngle   The angle of the exit location.
	 * @param destinationAngle    The angle towards the destination.
	 * @param differenceExitEntry The difference between exit and entry angles.
	 */
	private void addInvalidGateway(Gateway gateway, double locationExitAngle, double destinationAngle,
			double differenceExitEntry) {
		double cost = Angles.differenceAngles(locationExitAngle, destinationAngle);
		if (cost <= 90) {
			cost += differenceExitEntry;
			otherGateways.put(gateway.gatewayID, cost);
		}
	}

	/**
	 * Adds a valid gateway based on specific angles and differences, storing it in
	 * 'validGateways'.
	 *
	 * @param gateway             The gateway being evaluated.
	 * @param locationExitAngle   The angle of the exit location.
	 * @param destinationAngle    The angle towards the destination.
	 * @param differenceExitEntry The difference between exit and entry angles.
	 */
	private void addValidGateway(Gateway gateway, double locationExitAngle, double destinationAngle,
			double differenceExitEntry) {
		double cost;
		if (isCurrentExit(gateway))
			cost = Angles.differenceAngles(gateway.entryAngle, destinationAngle);
		else
			cost = Angles.differenceAngles(locationExitAngle, destinationAngle) + differenceExitEntry;
		validGateways.put(gateway.gatewayID, cost);
	}

	/**
	 * Identifies barriers within the regions traversed and inserts barrier
	 * sub-goals into the sequence of gateways when applicable.
	 *
	 * @return The sequence with added barrier sub-goals.
	 * @throws Exception
	 */
	private List<NodeGraph> regionalBarriers() throws Exception {

		List<NodeGraph> gatewaySequenceWithSubGoals = new ArrayList<>();
		BarrierBasedNavigation barrierBasedNavigation = new BarrierBasedNavigation(originNode, destinationNode, agent,
				true);

		for (NodeGraph gateway : gatewaySequence) {
			if (gatewaysToIgnore.contains(gateway))
				continue;
			gatewaySequenceWithSubGoals.add(gateway);
			if (gateway.equals(destinationNode))
				break;
			int indexOf = gatewaySequence.indexOf(gateway);
			// continue for exit gateways and destination
			if (indexOf > 0 && indexOf % 2 != 0)
				continue;

			// check if there are good barriers in line of movement towards the destination
			Region region = regionsMap.get(gateway.getRegionID());
			Map<Integer, Double> validBarriers = barrierBasedNavigation.findValidBarriers(gateway, region);
			if (validBarriers.isEmpty())
				continue;

			// given the best valid barriers, identify the best one and the relative
			// reference-edge
			Pair<EdgeGraph, Integer> barrierGoal = barrierBasedNavigation.identifyBarrierSubGoal(validBarriers, region);
			if (barrierGoal == null)
				continue;

			// gets the subGoal from one of the edges' nodes.
			NodeGraph subGoal = null;
			EdgeGraph edgeGoal = barrierGoal.getValue0();
			int barrierID = barrierGoal.getValue1();

			// pick the closest barrier sub-goal
			if (GraphUtils.nodesDistance(gateway, edgeGoal.getFromNode()) < GraphUtils.nodesDistance(gateway,
					edgeGoal.getToNode()))
				subGoal = edgeGoal.getFromNode();
			else
				subGoal = edgeGoal.getToNode();

			barrierBasedNavigation.visitedBarriers.add(barrierID);
			// if this subgoal it's already in the sequence, i.e if it's an exit, continue
			if (gatewaySequence.contains(subGoal))
				continue;
			gatewaySequenceWithSubGoals.add(subGoal);

			// if this is the entry gateway to the last region
			if (indexOf == gatewaySequence.size() - 2)
				continue;
			targetRegionID = gatewaySequence.get(indexOf + 2).getRegionID();

			// if subGoal is a newGateway itself and it leads to the next region
			if (subGoal.gateway && subGoal.adjacentRegions.contains(targetRegionID))
				updateGatewaySequence(subGoal, indexOf);
			// if the subGoal is not a gateway, check whether there's a better gateway
			// towards the next region (desiredRegion)
			else {
				Pair<NodeGraph, NodeGraph> newGateways = findNextGateway(subGoal, subGoal.getRegionID(),
						targetRegionID);
				if (newGateways == null)
					continue;
				gatewaySequence.set(indexOf + 1, newGateways.getValue0());
				gatewaySequence.set(indexOf + 2, newGateways.getValue1());
			}
		}
		return gatewaySequenceWithSubGoals;
	}

	/**
	 * Updates the gatewaySequence, if the barrier-subGoal is a newGateway itself
	 * and it leads to the next region; no need to go through another gateway
	 *
	 * @param subGoal The sub-goal node to process.
	 * @param indexOf The index of previously defined identified exit in the
	 *                sequence.
	 */
	private void updateGatewaySequence(NodeGraph subGoal, Integer indexOf) {

		// a) ignore the next exit
		gatewaysToIgnore.add(gatewaySequence.get(indexOf + 1));

		// b) get a new entry
		double deviation = Double.MAX_VALUE;
		NodeGraph bestEntry = null;
		for (NodeGraph entry : subGoal.adjacentRegionEntries) {
			if (entry.getRegionID() != targetRegionID)
				continue;
			double entryAngle = Angles.angle(subGoal, entry);
			if (entryAngle < deviation) {
				deviation = entryAngle;
				bestEntry = entry;
			}
		}
		// c) replace the old entry
		gatewaySequence.set(indexOf + 2, bestEntry);
	}
}
