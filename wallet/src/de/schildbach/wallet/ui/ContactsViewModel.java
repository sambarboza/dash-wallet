package de.schildbach.wallet.ui;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.ViewModel;

import de.schildbach.wallet.data.ContactsRepository;
import de.schildbach.wallet.data.ContactsState;
import de.schildbach.wallet.data.Resource;

/**
 * @author Samuel Barbosa
 */
public class ContactsViewModel extends ViewModel {

    ContactsRepository repository = ContactsRepository.getInstance();

    public LiveData<Resource<ContactsState>> getContacts(boolean loadData) {
        return repository.getContacts(loadData);
    }

    public LiveData<Resource<String>> addContact(String username, byte[] privKeyBytes) {
        return repository.addContact(username, privKeyBytes);
    }
}
