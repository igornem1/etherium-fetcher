package limechain.etherium.fetcher.service;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

import jakarta.validation.constraints.NotEmpty;
import limechain.etherium.fetcher.model.EthereumTransaction;
import limechain.etherium.fetcher.repository.TransactionRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class EheriumTransactionService {

    private final Web3j web3j;

    private static final int HEXAVAL_BEGIN_2 = 2;
    private static final int RADIX_16 = 16;

    private final TransactionRepository repository;

    public EheriumTransactionService(@Value("${ethereum.node.url}") String ethereumNodeUrl, TransactionRepository transactionRecordRepository) {
        this.web3j = Web3j.build(new HttpService(ethereumNodeUrl));
        this.repository = transactionRecordRepository;
    }

    public Collection<EthereumTransaction> findAll() {
        return repository.findAll();
    }

    public Collection<EthereumTransaction> findByHashList(@NotEmpty List<String> hashes) throws IOException, TransactionException {
        Set<String> sourceTransactions = new HashSet<>(hashes);
        Set<EthereumTransaction> existingTransactions = repository.findByTransactionHashIn(hashes);
        if (existingTransactions.size() != sourceTransactions.size()) {
            existingTransactions.stream().forEach(t -> sourceTransactions.remove(t.getTransactionHash()));
            Set<EthereumTransaction> remainTransactions = getFromBlockChain(sourceTransactions);

            List<BigInteger> values = new ArrayList<>();
            List<String> inputes = new ArrayList<>();
            List<Integer> logsCount = new ArrayList<>();
            List<String> contractAddresses = new ArrayList<>();
            List<String> to = new ArrayList<>();
            List<String> from = new ArrayList<>();
            List<BigInteger> blockNumbers = new ArrayList<>();
            List<String> blockHashes = new ArrayList<>();
            List<Boolean> transactionStatuses = new ArrayList<>();
            List<String> transactionHashes = new ArrayList<>();

            remainTransactions.forEach(transaction -> {
                values.add(transaction.getValue());
                inputes.add(transaction.getInput());
                logsCount.add(transaction.getLogsCount());
                contractAddresses.add(transaction.getContractAddress());
                to.add(transaction.getTo());
                from.add(transaction.getFrom());
                blockNumbers.add(transaction.getBlockNumber());
                blockHashes.add(transaction.getBlockHash());
                transactionStatuses.add(transaction.getTransactionStatus());
                transactionHashes.add(transaction.getTransactionHash());
            });

            repository.saveAllIfNotExists(values, inputes, logsCount, contractAddresses, to, from, blockNumbers, blockHashes, transactionStatuses, transactionHashes);
            existingTransactions.addAll(remainTransactions);
        }
        return existingTransactions;
    }

    public Collection<EthereumTransaction> findByRlphex(String rlphexHashes) throws IOException, TransactionException {
        return findByHashList(decodeRlpAndGetTransactions(rlphexHashes));
    }

    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private List<String> decodeRlpAndGetTransactions(String rlphex) {
        byte[] rlpEncodedBytes = hexStringToByteArray(rlphex);

        RlpList rlpList = RlpDecoder.decode(rlpEncodedBytes);
        List<String> transactionHashes = new ArrayList<>();

        RlpType mainElement = rlpList.getValues().get(0);
        if (mainElement instanceof RlpList) {
            RlpList mainList = (RlpList) mainElement;

            for (RlpType rlpType : mainList.getValues()) {
                if (rlpType instanceof RlpString) {
                    RlpString rlpString = (RlpString) rlpType;
                    transactionHashes.add(rlpString.asString());
                }
            }
        } else {
            throw new IllegalArgumentException("Wrong RLP format, exepected RlpList but got " + mainElement);
        }

        return transactionHashes;
    }

    private Set<EthereumTransaction> getFromBlockChain(Set<String> transactionHashes) throws IOException, TransactionException {
        log.debug("Looking transactions for list:" + transactionHashes);
        Set<EthereumTransaction> transactions = new HashSet<>();
        for (String txHash : transactionHashes) {
            Transaction tx = web3j.ethGetTransactionByHash(txHash).send().getTransaction().orElse(null);
            log.debug("Got tx via web3: " + tx);
            if (tx != null) {
                TransactionReceipt txReceipt = web3j.ethGetTransactionReceipt(tx.getHash()).send().getTransactionReceipt().orElse(null);
                transactions.add(toEthereumTransaction(tx, txReceipt));
            }
        }
        return transactions;
    }

    public EthereumTransaction toEthereumTransaction(Transaction tx, TransactionReceipt txReceipt) throws IOException, TransactionException {

        boolean transactionStatus = txReceipt != null && txReceipt.isStatusOK() ? true : false;
        BigInteger blockNumber = tx.getBlockNumber() != null ? tx.getBlockNumber() : BigInteger.ZERO;
        int logsCount = txReceipt != null ? txReceipt.getLogs().size() : 0;

        return new EthereumTransaction(null, tx.getHash(), transactionStatus, tx.getBlockHash(), blockNumber, tx.getFrom(), tx.getTo(), tx.getCreates(), logsCount, tx.getInput(),
                tx.getValue());
    }

}
