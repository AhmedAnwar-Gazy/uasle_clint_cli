package orgs.clintGUI;

import orgs.model.User;

import java.util.List;

/**
 * Listener for retrieving a list of user contacts.
 */
public interface OnContactsRetrievedListener {
    void onContactsRetrieved(List<User> contacts);
}
