package de.schildbach.wallet.ui;

import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import org.dashevo.dpp.Document;

import java.util.ArrayList;
import java.util.List;

import de.schildbach.wallet.data.DashPayContact;
import de.schildbach.wallet_test.R;

/**
 * @author Samuel Barbosa
 */
public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ContactViewHolder> {

    private List<Document> contacts = new ArrayList<>();
    private final ContactsFragment.ContactsType contactsType;
    private OnItemClickListener onItemClickListener;

    public ContactsAdapter(ContactsFragment.ContactsType contactsType) {
        this.contactsType = contactsType;
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        View view = null;
        switch (contactsType) {
            case CONTACT:
                view = inflater.inflate(R.layout.contact_item, viewGroup, false);
                break;
            case PENDING:
                view = inflater.inflate(R.layout.proposed_contact_item, viewGroup, false);
                break;
            case REQUEST:
                view = inflater.inflate(R.layout.contact_request_item, viewGroup, false);
                break;
        }
        return new ContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder contactViewHolder, int i) {
        contactViewHolder.bind(contacts.get(i));
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    public class ContactViewHolder extends RecyclerView.ViewHolder {

        TextView username;
        ImageButton acceptBtn;

        public ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            username = (TextView) itemView.findViewById(R.id.username);
            acceptBtn = (ImageButton) itemView.findViewById(R.id.acceptBtn);
        }

        void bind(Document contact) {
            switch (contactsType) {
                case CONTACT:
                    if (contact.getData().containsKey("username")) {
                        username.setText((String) contact.getData().get("username"));
                    } else {
                        username.setText((String) contact.getMeta().get("userId"));
                    }
                    break;
                case PENDING:
                    if (contact.getData().containsKey("username")) {
                        username.setText((String) contact.getData().get("username"));
                    } else {
                        username.setText((String) contact.getData().get("toUserId"));
                    }
                    break;
                case REQUEST:
                    if (contact.getData().containsKey("username")) {
                        username.setText((String) contact.getData().get("username"));
                    } else {
                        username.setText((String) contact.getMeta().get("userId"));
                    }
                    break;
            }
            if (acceptBtn != null) {
                acceptBtn.setOnClickListener(v -> {
                    if (onItemClickListener != null) {
                        onItemClickListener.onAddClicked(contact);
                    }
                });
            }
        }

    }

    public void remove(DashPayContact contact) {
        contacts.remove(contact);
        notifyDataSetChanged();
    }

    public void setContacts(List<Document> contacts) {
        this.contacts = contacts;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener onItemClickListener) {
        this.onItemClickListener = onItemClickListener;
    }

    interface OnItemClickListener {
        void onAddClicked(Document contact);
        void onRemoveClicked(Document contact);
    }
}
