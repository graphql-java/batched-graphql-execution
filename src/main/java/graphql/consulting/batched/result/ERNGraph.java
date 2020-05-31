package graphql.consulting.batched.result;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class ERNGraph {

    private Map<String, LinkedHashSet<Integer>> childrenMap = new LinkedHashMap<>();
    private Map<Integer, Integer> parentMap = new LinkedHashMap<>();
    private Map<Integer,Integer> parentPosition = new LinkedHashMap<>();

    private Map<Integer, ExecutionResultNode> nodes = new LinkedHashMap<>();

    public void replaceNode(ExecutionResultNode curNode, ExecutionResultNode newNode) {
        int curId = curNode.getId();
        nodes.remove(curId);
        nodes.put(newNode.getId(), newNode);

        Integer parentId = parentMap.get(curId);
    }

    public void addChild(ExecutionResultNode parent, ExecutionResultNode child) {

    }

    public static void main(String[] args) {

    }

}
