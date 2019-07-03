package de.schildbach.wallet.data;

import org.dashevo.dpp.Document;

import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class ContactsState {

    private final List<Document> sent;
    private final List<Document> received;
    private final List<Document> contacts;

    public ContactsState(List<Document> sent, List<Document> received, List<Document> contacts) {
        this.sent = sent;
        this.received = received;
        this.contacts = contacts;
    }

    public List<Document> getSent() {
        return sent;
    }

    public List<Document> getReceived() {
        return received;
    }

    public List<Document> getContacts() {
        return contacts;
    }

}
