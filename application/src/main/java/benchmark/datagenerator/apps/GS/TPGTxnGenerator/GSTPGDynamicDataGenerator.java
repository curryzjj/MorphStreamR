package benchmark.datagenerator.apps.GS.TPGTxnGenerator;
import benchmark.datagenerator.Event;
import benchmark.datagenerator.apps.GS.TPGTxnGenerator.Transaction.GSEvent;
import benchmark.dynamicWorkloadGenerator.DynamicDataGeneratorConfig;
import benchmark.dynamicWorkloadGenerator.DynamicWorkloadGenerator;
import common.tools.FastZipfGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.AppConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import static common.CONTROL.enable_log;
import static common.CONTROL.enable_states_partition;

public class GSTPGDynamicDataGenerator extends DynamicWorkloadGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(GSTPGDynamicDataGenerator.class);
    private int NUM_ACCESS; // transaction length, 4 or 8 or longer
    private int State_Access_Skewness; // ratio of state access, following zipf distribution
    private int Ratio_of_Transaction_Aborts; // ratio of transaction aborts, fail the transaction or not. i.e. transfer amount might be invalid.
    private int Ratio_of_Overlapped_Keys; // ratio of overlapped keys in transactions, which affects the dependencies and circulars.
    private int Transaction_Length;
    private int Ratio_of_Multiple_State_Access;//ratio of multiple state access per transaction
    private int tthread;
    private int nKeyState;
    // control the number of txns overlap with each other.
    private ArrayList<Integer> generatedKeys = new ArrayList<>();
    private HashMap<Integer, ArrayList<Integer>> generatedKeysByPartition = new HashMap<>();
    // independent transactions.
    private boolean isUnique = false;
    private FastZipfGenerator keyZipf;

    private int floor_interval;
    public FastZipfGenerator[] partitionedKeyZipf;

    private Random random = new Random(0); // the transaction type decider
    public transient FastZipfGenerator p_generator; // partition generator
    public transient FastZipfGenerator m_generator; // multiple partition generator
    private HashMap<Integer, Integer> nGeneratedIds = new HashMap<>();
    private ArrayList<Event> events;
    private int eventID = 0;
    private HashMap<Integer, Integer> idToLevel = new HashMap<>();

    public GSTPGDynamicDataGenerator(DynamicDataGeneratorConfig dynamicDataConfig) {
        super(dynamicDataConfig);
        events = new ArrayList<>(nTuples);
        tthread = dynamicDataConfig.getTotalThreads();
        for (int i = 0; i < tthread; i++) {
            generatedKeysByPartition.put(i, new ArrayList<>());
        }
    }

    @Override
    public void mapToTPGProperties() {
        //TD,LD,PD,VDD,R_of_A,isCD,isCC,
        StringBuilder stringBuilder = new StringBuilder();
        //TODO:hard code, function not sure
        double td = NUM_ACCESS * dynamicDataConfig.getCheckpoint_interval();
//        td = td * ((double) Ratio_of_Overlapped_Keys / 100);
        stringBuilder.append(td);
        stringBuilder.append(",");
        double ld = NUM_ACCESS * dynamicDataConfig.getCheckpoint_interval();
        stringBuilder.append(ld);
        stringBuilder.append(",");
        double pd = dynamicDataConfig.getCheckpoint_interval() * ((double) Ratio_of_Multiple_State_Access / 100) * NUM_ACCESS;
        stringBuilder.append(pd);
        stringBuilder.append(",");
        stringBuilder.append((double) State_Access_Skewness / 100);
        stringBuilder.append(",");
        stringBuilder.append((double) Ratio_of_Transaction_Aborts / 10000);
        stringBuilder.append(",");
        if (AppConfig.isCyclic) {
            stringBuilder.append("1,");
        } else {
            stringBuilder.append("0,");
        }
        if (AppConfig.complexity < 40000){
            stringBuilder.append("0,");
        } else {
            stringBuilder.append("1,");
        }
        stringBuilder.append(eventID + dynamicDataConfig.getShiftRate() * dynamicDataConfig.getCheckpoint_interval() * dynamicDataConfig.getTotalThreads());
        this.tranToDecisionConf.add(stringBuilder.toString());
    }

    @Override
    public void switchConfiguration(String type) {
        switch (type) {
            case "default" :
                State_Access_Skewness = dynamicDataConfig.State_Access_Skewness;
                NUM_ACCESS = dynamicDataConfig.NUM_ACCESS;
                Ratio_of_Transaction_Aborts = dynamicDataConfig.Ratio_of_Transaction_Aborts;
                Ratio_of_Overlapped_Keys = dynamicDataConfig.Ratio_of_Overlapped_Keys;
                Transaction_Length = dynamicDataConfig.Transaction_Length;
                Ratio_of_Multiple_State_Access = dynamicDataConfig.Ratio_of_Multiple_State_Access;

                nKeyState = dynamicDataConfig.getnKeyStates();
                int MAX_LEVEL = 256;
                for (int i = 0; i < nKeyState; i++) {
                    idToLevel.put(i, i % MAX_LEVEL);
                }
                keyZipf = new FastZipfGenerator(nKeyState, (double) State_Access_Skewness / 100, 0, 12345678);
                configure_store(1, (double) State_Access_Skewness / 100, dynamicDataConfig.getTotalThreads(), nKeyState);
                p_generator = new FastZipfGenerator(nKeyState, (double) State_Access_Skewness / 100, 0);
                m_generator = new FastZipfGenerator(nKeyState, 0.75, 0);
            break;
            case "skew" :
                keyZipf = new FastZipfGenerator(nKeyState, (double) State_Access_Skewness / 100, 0, 12345678);
                configure_store(1, (double) State_Access_Skewness / 100, dynamicDataConfig.getTotalThreads(), nKeyState);
                p_generator = new FastZipfGenerator(nKeyState, (double) State_Access_Skewness / 100, 0);
            break;
            case "PD" :
                Ratio_of_Multiple_State_Access = dynamicDataConfig.Ratio_of_Multiple_State_Access;
                if (Ratio_of_Multiple_State_Access > 50)
                    AppConfig.isCyclic = true;
            break;
            case "abort":
                Ratio_of_Transaction_Aborts = dynamicDataConfig.Ratio_of_Transaction_Aborts;
            case "unchanging" :
            break;
            default:
                throw new IllegalStateException("Unexpected value: " + type);
        }
        mapToTPGProperties();
    }

    @Override
    protected void generateTuple() {
        GSEvent event;
        event = randomEvent();
        events.add(event);
    }

    @Override
    public void dumpGeneratedDataToFile() {
        if (enable_log) LOG.info("++++++" + nGeneratedIds.size());

        if (enable_log) LOG.info("Dumping transactions...");
        try {
            dataOutputHandler.sinkEvents(events);
        } catch (IOException e) {
            e.printStackTrace();
        }

        File versionFile = new File(dynamicDataConfig.getRootPath().substring(0, dynamicDataConfig.getRootPath().length() - 1)
                + String.format("_%d.txt", dynamicDataConfig.getTotalEvents()));
        try {
            versionFile.createNewFile();
            FileWriter fileWriter = new FileWriter(versionFile);
            fileWriter.write(String.format("Total number of threads  : %d\n", dynamicDataConfig.getTotalThreads()));
            fileWriter.write(String.format("Total Events      : %d\n", dynamicDataConfig.getTotalEvents()));
            fileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void configure_store(double scale_factor, double theta, int tthread, int numItems) {
        floor_interval = (int) Math.floor(numItems / (double) tthread);//NUM_ITEMS / tthread;
        partitionedKeyZipf = new FastZipfGenerator[tthread];//overhead_total number of working threads.
        for (int i = 0; i < tthread; i++) {
            partitionedKeyZipf[i] = new FastZipfGenerator((int) (floor_interval * scale_factor), theta, i * floor_interval, 12345678);
        }
    }

    private GSEvent randomEvent() {
        int[] keys = new int[this.NUM_ACCESS * this.Transaction_Length];
        int writeLevel = -1;
        if (!isUnique) {
            if (enable_states_partition) {
                List<Integer> writeKeyPartitions = new ArrayList<>();
                int firstKeyPartition = key_to_partition(p_generator.next());//The partition id of the first write key
                if (random.nextInt(100) < Ratio_of_Multiple_State_Access) {//Need to access another partition
                    for (int i = 0; i < this.Transaction_Length; i++) {
                        while (writeKeyPartitions.contains(firstKeyPartition)) {
                            firstKeyPartition = key_to_partition(m_generator.next());
                        }
                        writeKeyPartitions.add(firstKeyPartition);
                    }
                    for (int j = 0; j < this.Transaction_Length; j++) {
                        int key = getKey(partitionedKeyZipf[writeKeyPartitions.get(j)]);
                        keys[j * this.NUM_ACCESS] = key; //First key is always write key

                        int readKeyPartition = key_to_partition(p_generator.next());//The partition ids of the read keys
                        for (int i = 1; i < this.NUM_ACCESS; i ++) {
                            int offset = j * this.NUM_ACCESS + i;
                            keys[offset] = getKey(partitionedKeyZipf[readKeyPartition]);
                            readKeyPartition = key_to_partition(p_generator.next());
                        }
                    }
                } else {
                    for (int i = 0; i < this.Transaction_Length; i++) {
                        writeKeyPartitions.add(firstKeyPartition);
                    }
                    for (int j = 0; j < this.Transaction_Length; j++) {
                        int key = getKey(partitionedKeyZipf[writeKeyPartitions.get(j)]);
                        keys[j * this.NUM_ACCESS] = key; //First key is always write key
                        for (int i = 1; i < this.NUM_ACCESS; i ++) {
                            int offset = j * this.NUM_ACCESS + i;
                            keys[offset] = key;
                        }
                    }
                }
            } else {//TODO: not fix this
                for (int i = 0; i < NUM_ACCESS; i++) {
                    if (AppConfig.isCyclic) {
                        keys[i] = getKey(keyZipf, generatedKeys);
                    } else {
                        keys[i] = getKey(keyZipf, generatedKeys);
                        if (i == 0) {
                            while (idToLevel.get(keys[i]) == 0) {
                                keys[i] = getKey(keyZipf, generatedKeys);
                            }
                            writeLevel = idToLevel.get(keys[i]);
                        }
                        while (writeLevel <= idToLevel.get(keys[i])) {
                            keys[i] = getKey(keyZipf, generatedKeys);
                        }
                    }
                }
            }
        } else {
            // TODO: add transaction length logic
            for (int i = 0; i <NUM_ACCESS; i++) {
                keys[i] = getUniqueKey(keyZipf, generatedKeys);
            }
        }
        // just for stats record
        for (int key : keys) {
            nGeneratedIds.put(key, nGeneratedIds.getOrDefault(key, 0) + 1);
        }

        GSEvent t;
        if (random.nextInt(10000) < Ratio_of_Transaction_Aborts) {
            t = new GSEvent(eventID, keys, this.Transaction_Length, true);
            abort_num ++;
        } else {
            t = new GSEvent(eventID, keys, this.Transaction_Length, false);
        }
        // increase the timestamp i.e. transaction id
        eventID++;
        return t;
    }

    public int key_to_partition(int key) {
        return (int) Math.floor((double) key / floor_interval);
    }

    private int getKey(FastZipfGenerator zipfGenerator) {
        int key;
        key = zipfGenerator.next();
        return key;
    }

    private int getKey(FastZipfGenerator zipfGenerator, ArrayList<Integer> generatedKeys) {
        int srcKey;
        srcKey = zipfGenerator.next();
        if (!generatedKeys.isEmpty()) {
            srcKey = generatedKeys.get(zipfGenerator.next() % generatedKeys.size());
        }
        return srcKey;
    }

    private int getUniqueKey(FastZipfGenerator zipfGenerator, ArrayList<Integer> generatedKeys) {
        int key;
        key = zipfGenerator.next();
        while (generatedKeys.contains(key)) {
            key = zipfGenerator.next();
        }
        generatedKeys.add(key);
        return key;
    }



}
