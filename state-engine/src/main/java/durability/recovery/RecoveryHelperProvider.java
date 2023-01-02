package durability.recovery;

import common.io.LocalFS.LocalDataInputStream;
import common.tools.Deserialize;
import durability.snapshot.SnapshotResult.SnapshotCommitInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RecoveryHelperProvider {
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryHelperProvider.class);
    public static SnapshotCommitInformation getLatestCommitSnapshotCommitInformation(File recoveryFile) throws IOException {
        List<SnapshotCommitInformation> commitInformation = new ArrayList<>();
        LocalDataInputStream inputStream = new LocalDataInputStream(recoveryFile);
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        try{
            while(true){
                int len = dataInputStream.readInt();
                byte[] lastSnapResultBytes=new byte[len];
                dataInputStream.readFully(lastSnapResultBytes);
                SnapshotCommitInformation SnapshotCommitInformation = (SnapshotCommitInformation) Deserialize.Deserialize(lastSnapResultBytes);
                commitInformation.add(SnapshotCommitInformation);
            }
        } catch (EOFException e){
            LOG.info("finish read the current.log");
        } finally {
            dataInputStream.close();
        }
        return commitInformation.get(commitInformation.size() - 1);
    }
}