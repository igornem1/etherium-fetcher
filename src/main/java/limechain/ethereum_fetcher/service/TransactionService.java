package limechain.ethereum_fetcher.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

import jakarta.transaction.Transactional;
import limechain.ethereum_fetcher.model.Transaction;
import limechain.ethereum_fetcher.model.User;
import limechain.ethereum_fetcher.repository.TransactionRepository;
import limechain.ethereum_fetcher.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TransactionService {

    private static final String ETHEREUM_NODE_URL = "${ethereum.node.url}";
    private final Web3j web3j;
    private final TransactionRepository repository;
    private final UserRepository userRepository;

    public TransactionService(@Value(ETHEREUM_NODE_URL) String ethereumNodeUrl, TransactionRepository transactionRecordRepository, UserRepository userRepository) {
        this.web3j = Web3j.build(new HttpService(ethereumNodeUrl));
        this.repository = transactionRecordRepository;
        this.userRepository = userRepository;
    }

    public Collection<Transaction> findAll() {
        return repository.findAll();
    }

    @Transactional
    public Collection<Transaction> findByHashList(List<String> hashes) throws IOException, TransactionException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        final User user = authentication.isAuthenticated() ? userRepository.findById(((User) authentication.getPrincipal()).getId()).orElseThrow() : null;
        if (user != null) {
            log.debug("User is authorized");
        } else {
            log.debug("User is not authorized, transactions won't be bound to his account");
        }
        Set<String> lookingHashes = new HashSet<>(hashes);
        log.debug("Looking transactions at DB for {} hashes: {}", lookingHashes.size(), lookingHashes);

        List<Transaction> existingTransactions = repository.findByHashIn(hashes);
        log.debug("Found {} transactions at DB", existingTransactions.size());

        if (existingTransactions.size() != lookingHashes.size()) {

            existingTransactions.forEach(t -> lookingHashes.remove(t.getHash()));
            log.debug("Looking transactions at blockchain for {} hashes: {}", lookingHashes.size(), lookingHashes);

            List<Transaction> remainTransactions = getFromBlockChain(lookingHashes);
            log.debug("Received {} transactions from blockchain, go to store them", remainTransactions.size());

            remainTransactions.forEach(transaction -> {
                try {
                    if (user != null) {
                        transaction.setUsers(Set.of(user));
                    }
                    transaction = repository.saveOne(transaction);
                } catch (DataIntegrityViolationException de) {
                    Throwable cause = de.getCause();
                    if (cause == null || cause.getClass() != ConstraintViolationException.class
                            || !Transaction.UQ_TRANSACTION_HASH.equals(((ConstraintViolationException) cause).getConstraintName())) {
                        log.error("Failed to store transaction due to: {}", de, ", transaction: {}", transaction);
                        throw new RuntimeException("Failed to store transaction at db. Transaction: " + transaction, de);
                    }
                }
                log.debug("Stored transaction: {}", transaction);
            });

            log.debug("Stored {} transactions", remainTransactions.size());
            existingTransactions.addAll(remainTransactions);
        }

        if (user != null) {
            user.getTransactions().addAll(existingTransactions);
            userRepository.save(user);
        }
        return existingTransactions;
    }

    @Transactional
    public Collection<Transaction> findByRlphex(String rlphexHashes) throws IOException, TransactionException {
        return findByHashList(decodeRlpAndGetTransactions(rlphexHashes));
    }

    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    static List<String> decodeRlpAndGetTransactions(String rlphex) {
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

    private List<Transaction> getFromBlockChain(Set<String> transactionHashes) throws IOException, TransactionException {
        List<Transaction> transactions = new ArrayList<>();
        for (String txHash : transactionHashes) {
            org.web3j.protocol.core.methods.response.Transaction tx = web3j.ethGetTransactionByHash(txHash).send().getTransaction().orElse(null);
            if (tx != null) {
                TransactionReceipt txReceipt = web3j.ethGetTransactionReceipt(tx.getHash()).send().getTransactionReceipt().orElse(null);
                Transaction ethereumTransaction = toEthereumTransaction(tx, txReceipt);
                transactions.add(ethereumTransaction);
            }
        }
        return transactions;
    }

    private static Transaction toEthereumTransaction(org.web3j.protocol.core.methods.response.Transaction tx, TransactionReceipt txReceipt)
            throws IOException, TransactionException {
        boolean transactionStatus = txReceipt != null && txReceipt.isStatusOK() ? true : false;
        int logsCount = txReceipt != null ? txReceipt.getLogs().size() : 0;
        return new Transaction(tx.getHash(), transactionStatus, tx.getBlockHash(), tx.getBlockNumber(), tx.getFrom(), tx.getTo(), tx.getCreates(), logsCount,
                tx.getInput(), tx.getValue(), null);
    }

}
