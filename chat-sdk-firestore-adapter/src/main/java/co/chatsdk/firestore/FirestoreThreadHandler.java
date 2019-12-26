package co.chatsdk.firestore;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import co.chatsdk.core.dao.DaoCore;
import co.chatsdk.core.dao.Keys;
import co.chatsdk.core.dao.Message;
import co.chatsdk.core.dao.Thread;
import co.chatsdk.core.dao.User;
import co.chatsdk.core.interfaces.ThreadType;
import co.chatsdk.core.session.ChatSDK;
import co.chatsdk.firebase.FirebaseThreadHandler;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.schedulers.Schedulers;
import sdk.chat.micro.chat.Chat;
import sdk.chat.micro.namespace.Fly;
import sdk.chat.micro.namespace.MicroUser;
import sdk.chat.micro.firebase.rx.DisposableList;
import sdk.chat.micro.types.RoleType;

public class FirestoreThreadHandler extends FirebaseThreadHandler {

    DisposableList disposableList = new DisposableList();

    public Completable sendMessage(final Message message) {

        HashMap<String, Object> messageBody = new HashMap<>();

        messageBody.put(Keys.Type, message.getType());
        messageBody.put(Keys.Meta, message.getMetaValuesAsMap());

        if (message.getThread().getType() == ThreadType.Private1to1) {
            User otherUser = message.getThread().otherUser();
            return Fly.y.sendMessageWithBody(otherUser.getEntityID(), messageBody)
                    .doOnSuccess(message::setEntityID)
                    .ignoreElement();
        } else {
            Chat chat = Fly.y.getChat(message.getThread().getEntityID());
            if (chat != null) {
                return chat.sendMessageWithBody(messageBody)
                        .doOnSuccess(message::setEntityID)
                        .ignoreElement();
            } else {
                return Completable.error(new Throwable("Chat chat doesn't exist"));
            }
        }
    }

    @Override
    public Single<Thread> createThread(final String name, final List<User> users) {
        return createThread(name, users, ThreadType.None);
    }

    @Override
    public Single<Thread> createThread(final String name, final List<User> users, final int type) {
        return createThread(name, users, type, null);
    }

    @Override
    public Single<Thread> createThread(String name, List<User> users, int type, String entityID) {
        return createThread(name, users,type, entityID, null);
    }

    @Override
    public Single<Thread> createThread(String name, List<User> users, final int type, String entityID, String imageURL) {
        return Single.create((SingleOnSubscribe<Thread>) e -> {

        // Make sure that the current user is in the list and
        // that they are not the first item
        ArrayList<User> allUsers = new ArrayList<>();
        allUsers.addAll(users);

        allUsers.remove(ChatSDK.currentUser());
        allUsers.add(ChatSDK.currentUser());

        Thread thread = ChatSDK.db().fetchThreadWithUsers(allUsers);
        if(thread != null) {
            e.onSuccess(thread);
        } else {

            thread = DaoCore.getEntityForClass(Thread.class);
            DaoCore.createEntity(thread);

            int threadType = type;

            if (type == ThreadType.None) {
                if (allUsers.size() == 2) {
                    threadType = ThreadType.Private1to1;
                } else {
                    threadType = ThreadType.PrivateGroup;
                }
            }

            if (threadType == ThreadType.Private1to1) {
                thread.setEntityID(ChatSDK.currentUserID());
            }

            thread.setCreator(ChatSDK.currentUser());
            thread.setCreationDate(new Date());
            thread.setType(threadType);
            thread.addUsers(allUsers);

            if (name != null && !name.isEmpty()) {
                thread.setName(name);
            }
            if(imageURL != null && !imageURL.isEmpty()) {
                thread.setImageUrl(imageURL);
            }

            Thread finalThread = thread;

            if(threadType == ThreadType.Private1to1) {
                if (allUsers.size() != 2) {
                    e.onError(new Throwable("Private chat needs two members"));
                } else {
                    e.onSuccess(thread);
                }
            } else {

                ArrayList<MicroUser> usersToAdd = new ArrayList<>();
                for (User u : allUsers) {
                    if (!u.isMe()) {
                        usersToAdd.add(new MicroUser(u.getEntityID(), RoleType.member()));
                    }
                }

                // We need to actually create the chat
                disposableList.add(Fly.y.createChat(name, imageURL, new ArrayList<>(usersToAdd)).subscribe((groupChat, throwable) -> {
                    if (throwable == null) {
                        finalThread.setEntityID(groupChat.getId());
                        e.onSuccess(finalThread);
                    } else {
                        e.onError(throwable);
                    }
                }));
            }
        }

        }).subscribeOn(Schedulers.single());
    }


}
