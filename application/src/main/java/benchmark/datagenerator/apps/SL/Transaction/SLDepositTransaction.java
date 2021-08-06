package benchmark.datagenerator.apps.SL.Transaction;

/**
 * Streamledger deposit transaction, which write without dependency.
 */
public class SLDepositTransaction extends SLTransaction {
    private int id;
    private int accountId;
    private int assetId;
    private int accountAmount;
    private int assetAmount;

    public SLDepositTransaction(int id, int accountId, int assetId) {
        this.id = id;
        this.accountId = accountId;
        this.assetId = assetId;
        this.accountAmount = 10;
        this.assetAmount = 10;
    }

    public SLDepositTransaction(int accountId, int assetId, int accountAmount, int assetAmount) {
        this.accountId = accountId;
        this.assetId = assetId;
        this.accountAmount = accountAmount;
        this.assetAmount = assetAmount;
    }

    public int getAccountId() {
        return accountId;
    }


    @Override
    public String toString() {
        return id + "," +
                accountId + "," +
                assetId;
    }

    @Override
    public String toString(int iterationNumber, int totalTransaction) {
        return (id + (iterationNumber * totalTransaction)) + "," +
                (accountId + iterationNumber) + "," +
                (assetId + iterationNumber);
    }
}
