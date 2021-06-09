package transaction.scheduler.nonlayered;

import common.OperationChain;

import java.util.ArrayList;

public class Listener implements IOnDependencyResolvedListener{

    private final ArrayList<OperationChain>[] leftOversLocal;
    private final ArrayList<OperationChain>[] withDependentsLocal;

    public Listener(ArrayList<OperationChain>[] leftOversLocal, ArrayList<OperationChain>[] withDependentsLocal) {
        this.leftOversLocal = leftOversLocal;
        this.withDependentsLocal = withDependentsLocal;
    }

    @Override
    public void onParentsResolvedListener(int threadId, OperationChain oc) {
        if (oc.hasChildren())
            withDependentsLocal[threadId].add(oc);
        else
            leftOversLocal[threadId].add(oc);
    }

}