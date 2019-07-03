package de.schildbach.wallet.ui;

import android.app.ProgressDialog;
import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import org.dash.wallet.common.util.ProgressDialogUtils;
import org.dashevo.dpp.Document;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.List;

import de.schildbach.wallet.data.StatusType;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsFragment extends Fragment {

    enum ContactsType {
        CONTACT,
        PENDING,
        REQUEST
    }

    private RecyclerView contactsRv;
    private ContactsAdapter adapter;
    private ProgressDialog loadingDialog;
    private ContactsViewModel viewModel;

    public static ContactsFragment newInstance(ContactsType type) {
        Bundle args = new Bundle();
        args.putInt("type", type.ordinal());

        ContactsFragment fragment = new ContactsFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_contacts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        int fragTypeInt = getArguments().getInt("type");
        ContactsType contactsType = ContactsType.values()[fragTypeInt];

        contactsRv = (RecyclerView) view.findViewById(R.id.contactsRv);
        contactsRv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ContactsAdapter(contactsType);
        contactsRv.setAdapter(adapter);

        viewModel = ViewModelProviders.of(this).get(ContactsViewModel.class);
        viewModel.getContacts().observe(this, contactsStateResource -> {
            switch (contactsType) {
                case CONTACT:
                    setContacts(contactsStateResource.data.getContacts());
                    break;
                case REQUEST:
                    setContacts(contactsStateResource.data.getReceived());
                    break;
                case PENDING:
                    setContacts(contactsStateResource.data.getSent());
                    break;
            }
        });

        adapter.setOnItemClickListener(new ContactsAdapter.OnItemClickListener() {
            @Override
            public void onAddClicked(Document contact) {
                UnlockWalletDialogFragment.show(getChildFragmentManager(), null,
                        encryptionKey -> addContact(contact, encryptionKey));
            }

            @Override
            public void onRemoveClicked(Document contact) {

            }
        });
    }

    private void addContact(Document contact, KeyParameter encryptionKey) {
        if (contact.getData().containsKey("username")) {
            viewModel.addContact((String) contact.getData().get("username"),
                    encryptionKey.getKey()).observe(this, contactResource -> {
                        StatusType status = contactResource.status;
                        if (status.isLoading()) {
                            showLoading();
                        } else {
                            hideLoading();
                            if (status.isSuccess()) {
                                Toast.makeText(getContext(),
                                        "Success! Please wait for a block to see the changes reflected.",
                                        Toast.LENGTH_SHORT).show();
                                viewModel.getContacts();
                            } else {
                                Toast.makeText(getContext(), "Error", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
        }
    }

    public void setContacts(List<Document> contacts) {
        adapter.setContacts(contacts);
    }

    private void showLoading() {
        if (loadingDialog == null) {
            loadingDialog = ProgressDialogUtils.createSpinningLoading(getContext());
        }
        loadingDialog.show();
    }

    private void hideLoading() {
        if (loadingDialog != null) {
            loadingDialog.cancel();
        }
    }
}
