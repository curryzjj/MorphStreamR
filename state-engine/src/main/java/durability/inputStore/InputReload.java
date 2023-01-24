package durability.inputStore;

import common.io.Compressor.Compressor;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Queue;

public abstract class InputReload {
    public int partitionOffset;
    public int tthread;
    public Compressor inputCompressor;

    public abstract void reloadInput(BufferedReader bufferedReader, Queue<Object> lostEvents, long redoOffset) throws IOException;
}
