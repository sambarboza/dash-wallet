package de.schildbach.wallet.data;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.arch.lifecycle.MutableLiveData;
import android.util.Log;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.dashevo.dapiclient.DapiClient;
import org.dashevo.dapiclient.callback.DapiRequestCallback;
import org.dashevo.dapiclient.model.JsonRPCResponse;
import org.dashevo.dpp.Contract;
import org.dashevo.dpp.Document;
import org.dashevo.dpp.DocumentFactory;
import org.jetbrains.annotations.NotNull;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.util.WalletUtils;

/**
 * @author Samuel Barbosa
 */
public class ContactsRepository {

    private static ContactsRepository instance;
    private Executor executor;
    private MutableLiveData<Resource<List<Document>>> receivedRequests; //Received
    private MutableLiveData<Resource<List<Document>>> sentRequests; //Sent
    MediatorLiveData<Resource<ContactsState>> contacts; //Match

    private ContactsRepository() {
        executor = Executors.newSingleThreadExecutor();
        receivedRequests = new MutableLiveData<>();
        sentRequests = new MutableLiveData<>();

        receivedRequests.postValue(new Resource<>(LoadingType.DEFAULT, Collections.emptyList()));
        sentRequests.postValue(new Resource<>(LoadingType.DEFAULT, Collections.emptyList()));

        contacts = new MediatorLiveData<>();
        contacts.addSource(receivedRequests, listResource -> deriveContactsState());
        contacts.addSource(sentRequests, listResource -> deriveContactsState());
    }

    public static ContactsRepository getInstance() {
        if (instance == null) {
            instance = new ContactsRepository();
        }
        return instance;
    }

    //TODO: We should probably make DapiClient singleton and make contractId a field of it.
    //However, we need to evaluate the need and possibility of fetching data from multiple contracts
    //from a single DapiClient instance
    private DapiClient dapiClient = new DapiClient("http://devnet-porto.thephez.com",
            "3000", false);
    private String contractId = "2TRFRpoGu3BpBKfFDmhbJJdDPzLdW4qbdfebkbeCHwj3";

    private Resource<List<Document>> toDocumentsList(List<Map<String, Object>> mapList) {
        List<Document> contactsDocuments = new ArrayList<>();
        Resource<List<Document>> contacts = new Resource<>(SuccessType.DEFAULT, contactsDocuments);
        for (Map<String, Object> contactDocument : mapList) {
            contactsDocuments.add(new Document(contactDocument));
        }
        return contacts;
    }

    private void getContactRequests(String userId) {
        Map<String, String> query = new HashMap<String, String>() {{
            put("document.toUserId", userId);
        }};
        dapiClient.fetchDocuments(contractId, "contact", new DapiRequestCallback<List<Map<String, Object>>>() {
            @Override
            public void onSuccess(@NotNull JsonRPCResponse<List<Map<String, Object>>> jsonRPCResponse) {
                Resource<List<Document>> documents = toDocumentsList(jsonRPCResponse.getResult());
                receivedRequests.postValue(documents);
            }

            @Override
            public void onError(@NotNull String s) {
                Resource<List<Document>> errorResource = new Resource<>(ErrorType.DEFAULT, Collections.emptyList());
                receivedRequests.postValue(errorResource);
            }
        }, query);
    }

    private void getPendingContacts(String userId) {
        Map<String, String> query = new HashMap<String, String>() {{
            put("userId", userId);
        }};
        dapiClient.fetchDocuments(contractId, "contact", new DapiRequestCallback<List<Map<String, Object>>>() {
            @Override
            public void onSuccess(@NotNull JsonRPCResponse<List<Map<String, Object>>> jsonRPCResponse) {
                Resource<List<Document>> documents = toDocumentsList(jsonRPCResponse.getResult());
                sentRequests.postValue(documents);
            }

            @Override
            public void onError(@NotNull String s) {
                Resource<List<Document>> errorResource = new Resource<>(ErrorType.DEFAULT, Collections.emptyList());
                sentRequests.postValue(errorResource);
            }
        }, query);
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    private void deriveContactsState() {
        if (receivedRequests.getValue().data.isEmpty() || sentRequests.getValue().data.isEmpty()) {
            return;
        }

        List<Document> sent = sentRequests.getValue().data;
        List<Document> received = receivedRequests.getValue().data;

        List<String> sentUserIds = new ArrayList<>();
        List<String> receivedUserIds = new ArrayList<>();

        for (Document s : sent) {
            sentUserIds.add((String) s.getData().get("toUserId"));
        }

        for (Document r : received) {
            receivedUserIds.add((String) r.getMeta().get("userId"));
        }

        List<Document> filteredSent = new ArrayList<>();
        List<Document> filteredReceived = new ArrayList<>();
        List<Document> contacts = new ArrayList<>();


        for (Document s : sent) {
            if (!receivedUserIds.contains(s.getData().get("toUserId"))) {
                filteredSent.add(s);
            }
        }

        for (Document r : received) {
            if (sentUserIds.contains(r.getMeta().get("userId"))) {
                contacts.add(r);
            } else {
                filteredReceived.add(r);
            }
        }

        ContactsState contactsState = new ContactsState(filteredSent, filteredReceived, contacts);
        this.contacts.postValue(new Resource<>(SuccessType.DEFAULT, contactsState));
        loadUsernames(contactsState);
    }

    //TODO: Remove after displayName in DashPay contract
    private void loadUsernames(ContactsState contactsState) {
        List<String> userIds = new ArrayList<>();

        for (Document d : contactsState.getContacts()) {
            userIds.add((String) d.getMeta().get("userId"));
        }
        for (Document d : contactsState.getReceived()) {
            userIds.add((String) d.getMeta().get("userId"));
        }
        for (Document d : contactsState.getSent()) {
            userIds.add((String) d.getData().get("toUserId"));
        }

        for (String userId : userIds) {
            dapiClient.getUser(userId, new DapiRequestCallback<org.dashevo.dapiclient.model.BlockchainUser>() {
                @Override
                public void onSuccess(@NotNull JsonRPCResponse<org.dashevo.dapiclient.model.BlockchainUser> jsonRPCResponse) {
                    org.dashevo.dapiclient.model.BlockchainUser blockchainUser = jsonRPCResponse.getResult();
                    for (Document d : contactsState.getContacts()) {
                        String userId = (String) d.getMeta().get("userId");
                        if (userId.equals(blockchainUser.getRegtxid())) {
                            d.getData().put("username", blockchainUser.getUname());
                        }
                    }
                    for (Document d : contactsState.getReceived()) {
                        String userId = (String) d.getMeta().get("userId");
                        if (userId.equals(blockchainUser.getRegtxid())) {
                            d.getData().put("username", blockchainUser.getUname());
                        }
                    }
                    for (Document d : contactsState.getSent()) {
                        String userId = (String) d.getData().get("toUserId");
                        if (userId.equals(blockchainUser.getRegtxid())) {
                            d.getData().put("username", blockchainUser.getUname());
                        }
                    }
                    contacts.postValue(new Resource<>(SuccessType.DEFAULT, contactsState));
                }

                @Override
                public void onError(@NotNull String s) {

                }
            });
        }
    }

    public MediatorLiveData<Resource<ContactsState>> getContacts() {
        sentRequests.postValue(new Resource<>(LoadingType.DEFAULT, Collections.emptyList()));
        receivedRequests.postValue(new Resource<>(LoadingType.DEFAULT, Collections.emptyList()));
        executor.execute(() -> {
            BlockchainUser user = AppDatabase.getAppDatabase().blockchainUserDao().getSync();
            getPendingContacts(user.getRegtxid());
            getContactRequests(user.getRegtxid());
        });
        return contacts;
    }

    public LiveData<Resource<String>> addContact(String username, byte[] encryptionKeyBytes) {
        MutableLiveData<Resource<String>> liveData = new MutableLiveData<>();
        liveData.postValue(new Resource<>(LoadingType.DEFAULT, ""));
        executor.execute(() -> {
            BlockchainUser user = AppDatabase.getAppDatabase().blockchainUserDao().getSync();
            KeyParameter keyParameter = new KeyParameter(encryptionKeyBytes);
            ECKey privKey = WalletUtils.getUserPrivateKey(keyParameter);
            Sha256Hash userRegTxHash = Sha256Hash.wrap(user.getRegtxid());
            List<String> stateTxHashes = user.getSubtx();
            int stateTxCount = stateTxHashes.size();
            Sha256Hash lastStateTxHash = Sha256Hash.wrap(stateTxHashes.get(stateTxCount - 1));

            dapiClient.fetchContract(contractId, new DapiRequestCallback<org.dashevo.dpp.Contract>() {
                @Override
                public void onSuccess(JsonRPCResponse<org.dashevo.dpp.Contract> response) {
                    Contract contract = response.getResult();
                    contract.setId(contractId);
                    dapiClient.getUser(username, new DapiRequestCallback<org.dashevo.dapiclient.model.BlockchainUser>() {
                        @Override
                        public void onSuccess(@NotNull JsonRPCResponse<org.dashevo.dapiclient.model.BlockchainUser> jsonRPCResponse) {
                            sendContactRequest(user, jsonRPCResponse.getResult(), userRegTxHash, lastStateTxHash,
                                    contract, privKey, liveData);
                        }

                        @Override
                        public void onError(@NotNull String s) {
                            liveData.postValue(new Resource<>(ErrorType.DEFAULT, s));
                        }
                    });

                }

                @Override
                public void onError(String s) {
                    liveData.postValue(new Resource<>(ErrorType.DEFAULT, s));
                }
            });
        });

        return liveData;
    }

    private void sendContactRequest(BlockchainUser user, org.dashevo.dapiclient.model.BlockchainUser contactUser,
                                    Sha256Hash userRegTxHash, Sha256Hash lastStateTxHash,
                                    Contract contract, ECKey privKey, MutableLiveData<Resource<String>> liveData) {

        String userId = userRegTxHash.toString();
        DocumentFactory documentFactory = new DocumentFactory(userId, contract);

        HashMap<String, Object> contact = new HashMap<>();
        contact.put("toUserId", contactUser.getRegtxid());
        //TODO: generate a specific key to this relationship
        contact.put("publicKey", user.getPubkeyid());

        HashMap<String, Object> profileMeta = new HashMap<>();
        profileMeta.put("userId", userId);

        Document document = documentFactory.create("contact", contact);
        document.setMeta(profileMeta);

        dapiClient.sendDapObject(document.toJSON(), contractId, userRegTxHash,
                lastStateTxHash, privKey, new DapiRequestCallback<String>() {
                    @Override
                    public void onSuccess(@NotNull JsonRPCResponse<String> jsonRPCResponse) {
                        liveData.postValue(new Resource<>(SuccessType.DEFAULT, jsonRPCResponse.getResult()));
                    }

                    @Override
                    public void onError(@NotNull String s) {
                        liveData.postValue(new Resource<>(ErrorType.DEFAULT, s));
                    }
                });

    }

}

