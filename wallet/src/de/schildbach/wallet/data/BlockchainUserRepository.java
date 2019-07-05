/*
 * Copyright © 2019 Dash Core Group. All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.data;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.Transformations;
import android.util.Log;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.RejectedTransactionException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.evolution.SubTxRegister;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.dashevo.dapiclient.DapiClient;
import org.dashevo.dapiclient.callback.DapiRequestCallback;
import org.dashevo.dapiclient.model.JsonRPCResponse;
import org.dashevo.dpp.Contract;
import org.dashevo.dpp.Document;
import org.dashevo.dpp.DocumentFactory;
import org.jetbrains.annotations.NotNull;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import de.schildbach.wallet.AppDatabase;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.util.WalletUtils;

/**
 * @author Samuel Barbosa
 */
public class BlockchainUserRepository {

    private static BlockchainUserRepository instance;
    private Executor executor;
    private MutableLiveData<Resource<BlockchainUser>> userLiveData;

    private BlockchainUserRepository() {
        executor = Executors.newSingleThreadExecutor();
        userLiveData = new MutableLiveData<>();
    }

    public static BlockchainUserRepository getInstance() {
        if (instance == null) {
            instance = new BlockchainUserRepository();
        }
        return instance;
    }

    private DapiClient dapiClient = new DapiClient("http://devnet-porto.thephez.com",
            "3000", true);
    private String contractId = "2TRFRpoGu3BpBKfFDmhbJJdDPzLdW4qbdfebkbeCHwj3";

    public LiveData<Resource<Transaction>> createBlockchainUser(final String username, byte[] encryptionKeyBytes) {
        WalletApplication application = WalletApplication.getInstance();
        Wallet wallet = application.getWallet();
        final MutableLiveData<Resource<Transaction>> liveData = new MutableLiveData<>();

        liveData.postValue(new Resource<>(LoadingType.DEFAULT, null));
        executor.execute(() -> {
            Context.propagate(Constants.CONTEXT);
            KeyParameter encryptionKey = new KeyParameter(encryptionKeyBytes);

            ECKey privKey = WalletUtils.getUserPrivateKey(encryptionKey);
            SubTxRegister subTxRegister = new SubTxRegister(1, username, privKey);
            SendRequest request = SendRequest.forSubTxRegister(wallet.getParams(),
                    subTxRegister, Coin.MILLICOIN);

            request.aesKey = encryptionKey;
            final Wallet.SendResult result;

            try {
                result = wallet.sendCoins(request);
                Futures.addCallback(result.broadcastComplete, new FutureCallback<Transaction>() {
                    @Override
                    public void onSuccess(@Nullable Transaction result) {
                        storeBlockchainUser(result, username);
                        //TODO: enable when able to create profile while user is in mempool
                        //createDashPayProfile(subTxRegister, subTxRegister, privKey);
                        liveData.postValue(new Resource<>(SuccessType.DEFAULT, result));
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        if (t instanceof RejectedTransactionException) {
                            liveData.postValue(new Resource<>(ErrorType.TX_REJECT_DUP_USERNAME, null));
                        } else {
                            liveData.postValue(new Resource<>(ErrorType.DEFAULT, null));
                        }
                    }
                }, Executors.newSingleThreadExecutor());
            } catch (InsufficientMoneyException e) {
                liveData.postValue(new Resource<>(ErrorType.INSUFFICIENT_MONEY, null));
            }
        });

        return liveData;
    }

    public void createDashPayProfile(BlockchainUser user, KeyParameter encryptionKey) {
        ECKey privKey = WalletUtils.getUserPrivateKey(encryptionKey);
        Sha256Hash subTxHash = Sha256Hash.wrap(user.getRegtxid());
        List<String> stateTxHashes = user.getSubtx();
        int stateTxCount = stateTxHashes.size();

        Sha256Hash lastStateTxHash = null;

        if (stateTxCount > 0) {
            String lastStateTxHashStr = stateTxHashes.get(stateTxCount - 1);
            if (!lastStateTxHashStr.isEmpty()) {
                lastStateTxHash = Sha256Hash.wrap(lastStateTxHashStr);
            }
        }
        if (lastStateTxHash == null) {
            lastStateTxHash = Sha256Hash.wrap(user.getRegtxid());
        }

        createDashPayProfile(user.getUname(), subTxHash, lastStateTxHash, privKey);
    }

    public void createDashPayProfile(String username, Sha256Hash userRegTxHash,
                                     Sha256Hash lastStateTxHash, ECKey privKey) {
        dapiClient.fetchContract(contractId, new DapiRequestCallback<org.dashevo.dpp.Contract>() {
            @Override
            public void onSuccess(JsonRPCResponse<org.dashevo.dpp.Contract> response) {
                String userId = userRegTxHash.toString();
                Contract contract = response.getResult();
                contract.setId(contractId);
                DocumentFactory documentFactory = new DocumentFactory(userId, contract);

                HashMap<String, Object> profile = new HashMap<>();
                profile.put("about", "Hey! I'm a DashPayNativeDemo user " + username);
                profile.put("avatarUrl", "https://api.adorable.io/avatars/120/" + username + ".png");

                HashMap<String, Object> profileMeta = new HashMap<>();
                profileMeta.put("userId", userId);

                Document document = documentFactory.create("profile", profile);
                document.setMeta(profileMeta);

                dapiClient.sendDapObject(document.toJSON(), contractId, userRegTxHash,
                        lastStateTxHash, privKey, new DapiRequestCallback<String>() {
                            @Override
                            public void onSuccess(@NotNull JsonRPCResponse<String> jsonRPCResponse) {
                                Log.d("ProfileCreation", jsonRPCResponse.toString());
                            }

                            @Override
                            public void onError(@NotNull String s) {
                                Log.d("ProfileCreation", s);
                            }
                        });

            }

            @Override
            public void onError(String s) {

            }
        });
    }

    private void storeBlockchainUser(Transaction regTx, String username) {
        ArrayList<String> subTxList = new ArrayList<>(1);
        String regTxId = regTx.getHashAsString();
        subTxList.add(regTxId);
        BlockchainUser blockchainUser = new BlockchainUser(regTxId, username, "",
                regTx.getInputSum(), "", "open", subTxList);
        AppDatabase.getAppDatabase().blockchainUserDao().insert(blockchainUser);
    }

    public LiveData<Resource<BlockchainUser>> getUser() {
        userLiveData.postValue(new Resource<>(LoadingType.DEFAULT, null));
        LiveData<BlockchainUser> buLiveData = AppDatabase.getAppDatabase().blockchainUserDao().get();

        final Observer<BlockchainUser> buObserver = new Observer<BlockchainUser>() {
            @Override
            public void onChanged(@android.support.annotation.Nullable BlockchainUser user) {
                buLiveData.removeObserver(this);
                if (user != null) {
                    dapiClient.getUser(user.getUname(), new DapiRequestCallback<org.dashevo.dapiclient.model.BlockchainUser>() {
                        @Override
                        public void onSuccess(@NotNull JsonRPCResponse<org.dashevo.dapiclient.model.BlockchainUser> jsonRPCResponse) {
                            executor.execute(() -> {
                                BlockchainUser blockchainUser = BlockchainUser.fromDapiClientObject(jsonRPCResponse.getResult());
                                AppDatabase.getAppDatabase().blockchainUserDao().insert(blockchainUser);
                            });
                        }

                        @Override
                        public void onError(@NotNull String s) {

                        }
                    });
                } else {
                    userLiveData.postValue(new Resource<>(ErrorType.DEFAULT, null));
                }
            }
        };
        buLiveData.observeForever(buObserver);


        return Transformations.map(buLiveData, input -> new Resource<>(SuccessType.DEFAULT, input));
    }

    public LiveData<Resource<Map<String, Object>>> getDashPayProfile(BlockchainUser user) {
        MutableLiveData<Resource<Map<String, Object>>> liveData = new MutableLiveData<>();

        Map<String, String> query = new HashMap<String, String>() {{
            put("userId", user.getRegtxid());
        }};

        dapiClient.fetchDocuments(contractId, "profile", new DapiRequestCallback<List<Map<String, Object>>>() {
            @Override
            public void onSuccess(@NotNull JsonRPCResponse<List<Map<String, Object>>> jsonRPCResponse) {
                List<Map<String, Object>> profiles = jsonRPCResponse.getResult();
                if (!profiles.isEmpty()) {
                    Map<String, Object> profileMap = profiles.get(0);
                    liveData.postValue(new Resource<>(SuccessType.DEFAULT, profileMap));
                } else {
                    liveData.postValue(new Resource<>(ErrorType.DEFAULT, null));
                }
            }

            @Override
            public void onError(@NotNull String s) {
                liveData.postValue(new Resource<>(ErrorType.DEFAULT, null));
            }
        }, query);

        liveData.postValue(new Resource<>(LoadingType.DEFAULT, null));
        return liveData;
    }

}
