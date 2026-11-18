package com.company;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.business.GetBusinessConnection;
import org.telegram.telegrambots.meta.api.methods.send.*;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.business.BusinessConnection;
import org.telegram.telegrambots.meta.api.objects.business.BusinessMessagesDeleted;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class DeletedBot implements LongPollingSingleThreadUpdateConsumer {
    // !!! BotFather'dan olgan YANGI tokeningizni shu yerga yozing
    private static final String BOT_TOKEN = "9033265477:AAFiRYWKXjrANF-McHgke_tGKu48Vnrum_Q";

    // !!! BotFather'dan olgan tokeningizni shu yerga yozing

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Saved(String type, String text, String fileId, String uniqueId, String caption,
                 String senderName, String chatName) {}

    private final TelegramClient client;

    private final Map<String, Long> ownerChat = new ConcurrentHashMap<>();
    private final Map<String, Long> ownerUser = new ConcurrentHashMap<>();
    private final Map<String, Saved> cache = new ConcurrentHashMap<>();

    public DeletedBot(String token) {
        this.client = new OkHttpTelegramClient(token);
    }

    public static void main(String[] args) throws Exception {
        TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication();
        app.registerBot(BOT_TOKEN, new DeletedBot(BOT_TOKEN));
        System.out.println("Bot ishga tushdi...");
        Thread.currentThread().join();

    }

    @Override
    public void consume(Update update) {

        try {
            // 1) Ulanish / uzilish
            if (update.getBusinessConnection() != null) {
                BusinessConnection bc = update.getBusinessConnection();
                if (bc.getIsEnabled()) {
                    register(bc);
                    send(bc.getUserChatId(), "✅ Bot akkauntingizga ulandi. O'chirilgan va tahrirlangan xabarlar shu yerga yuboriladi.");
                } else {
                    ownerChat.remove(bc.getId());
                    ownerUser.remove(bc.getId());
                    send(bc.getUserChatId(), "❌ Bot akkauntingizdan uzildi.");
                }
                return;
            }

            // 2) Yangi xabar
            if (update.getBusinessMessage() != null) {
                save(update.getBusinessMessage());
                return;
            }

            // 3) Tahrirlangan xabar (getter nomiga bog'liq bo'lmagan usul)
            Message edited = findEditedMessage(update);
            if (edited != null) {
                handleEdit(edited);
                return;
            }

            // 4) O'chirilgan xabarlar
            if (update.getDeletedBusinessMessages() != null) {
                BusinessMessagesDeleted d = update.getDeletedBusinessMessages();
                Long owner = ownerOf(d.getBusinessConnectionId());
                if (owner == null) {
                    return;
                }
                for (Integer id : d.getMessageIds()) {
                    String key = key(d.getBusinessConnectionId(), d.getChat().getId(), id);
                    Saved s = cache.remove(key);
                    if (s != null) {
                        String header = "🗑 Xabar o'chirildi\n👤 " + s.senderName() + "\n💬 Chat: " + s.chatName();
                        deliver(owner, s, header);
                    } else {
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ------------------------------------------------------- tahrirlash

    /** Update ichidan "edited business message" maydonini JSON orqali topadi. */
    private Message findEditedMessage(Update update) {
        try {
            JsonNode root = MAPPER.valueToTree(update);
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String n = e.getKey().toLowerCase();
                if (n.contains("edit") && n.contains("business") && !e.getValue().isNull()) {
                    return MAPPER.treeToValue(e.getValue(), Message.class);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void handleEdit(Message m) throws Exception {
        String connId = m.getBusinessConnectionId();
        if (connId == null) return;

        Long owner = ownerOf(connId);
        User from = m.getFrom();
        if (from != null && from.getId().equals(ownerUser.get(connId))) return;

        String key = key(connId, m.getChatId(), m.getMessageId());
        Saved old = cache.get(key);
        Saved now = toSaved(m);

        if (old != null && owner != null) {
            String header = "✏️ Xabar tahrirlandi\n👤 " + old.senderName() + "\n💬 Chat: " + old.chatName();

            // Media almashtirilgan bo'lsa -> eski mediani yuboramiz
            if (old.fileId() != null && !Objects.equals(old.uniqueId(), now.uniqueId())) {
                deliver(owner, old, header + "\n\n(Eski media)");
            }

            // Matn/izoh o'zgargan bo'lsa -> eski va yangini yuboramiz
            String oldText = old.text() != null ? old.text() : old.caption();
            String newText = now.text() != null ? now.text() : now.caption();
            if (!Objects.equals(oldText, newText)) {
                send(owner, header + "\n\nEski: " + nvl(oldText) + "\nYangi: " + nvl(newText));
            }
        }
        cache.put(key, now); // keshni yangilaymiz
    }

    // ------------------------------------------------------- ega aniqlash

    private void register(BusinessConnection bc) {
        ownerChat.put(bc.getId(), bc.getUserChatId());
        ownerUser.put(bc.getId(), bc.getUser().getId());
    }

    private Long ownerOf(String connId) {
        Long id = ownerChat.get(connId);
        if (id != null) return id;
        try {
            BusinessConnection bc = client.execute(
                    GetBusinessConnection.builder().businessConnectionId(connId).build());
            register(bc);
            return bc.getUserChatId();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // ------------------------------------------------------- saqlash

    private void save(Message m) {
        String connId = m.getBusinessConnectionId();
        if (connId == null) return;

        ownerOf(connId);

        User from = m.getFrom();
        if (from != null && from.getId().equals(ownerUser.get(connId))) return;

        Saved s = toSaved(m);
        cache.put(key(connId, m.getChatId(), m.getMessageId()), s);

        Long ownerId = ownerOf(connId);
        if (ownerId != null && (s.type().equals("photo") || s.type().equals("video"))) {
            try {
                String title = s.type().equals("photo") ? "📷 Yangi rasm" : "🎥 Yangi video";
                deliver(ownerId, s, title + "\n👤 " + s.senderName() + "\n💬 Chat: " + s.chatName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private Saved toSaved(Message m) {
        String type = "text";
        String fileId = null;
        String uniqueId = null;

        if (m.hasPhoto()) {
            List<PhotoSize> photos = m.getPhoto();
            PhotoSize p = photos.get(photos.size() - 1);
            fileId = p.getFileId();
            uniqueId = p.getFileUniqueId();
            type = "photo";
        } else if (m.hasVideo()) {
            fileId = m.getVideo().getFileId();
            uniqueId = m.getVideo().getFileUniqueId();
            type = "video";
        } else if (m.hasVoice()) {
            fileId = m.getVoice().getFileId();
            uniqueId = m.getVoice().getFileUniqueId();
            type = "voice";
        } else if (m.hasVideoNote()) {
            fileId = m.getVideoNote().getFileId();
            uniqueId = m.getVideoNote().getFileUniqueId();
            type = "video_note";
        } else if (m.hasAudio()) {
            fileId = m.getAudio().getFileId();
            uniqueId = m.getAudio().getFileUniqueId();
            type = "audio";
        } else if (m.hasAnimation()) {
            fileId = m.getAnimation().getFileId();
            uniqueId = m.getAnimation().getFileUniqueId();
            type = "animation";
        } else if (m.hasDocument()) {
            fileId = m.getDocument().getFileId();
            uniqueId = m.getDocument().getFileUniqueId();
            type = "document";
        } else if (m.hasSticker()) {
            fileId = m.getSticker().getFileId();
            uniqueId = m.getSticker().getFileUniqueId();
            type = "sticker";
        }

        User from = m.getFrom();
        String chatName = name(m.getChat().getFirstName(), m.getChat().getLastName(),
                m.getChat().getUserName(), m.getChat().getTitle());
        String senderName = from == null ? "Noma'lum"
                : name(from.getFirstName(), from.getLastName(), from.getUserName(), null);

        return new Saved(type, m.getText(), fileId, uniqueId, m.getCaption(), senderName, chatName);
    }

    // ------------------------------------------------------- yuborish

    /** Saqlangan xabarni (matn yoki media) egasiga yuboradi. */
    private void deliver(long owner, Saved s, String header) throws Exception {
        if (s.fileId() == null) {
            send(owner, header + "\n\n" + nvl(s.text()));
            return;
        }

        String cap = header + (s.caption() != null ? "\n\n" + s.caption() : "");
        InputFile f = new InputFile(s.fileId());

        switch (s.type()) {
            case "photo" -> client.execute(SendPhoto.builder().chatId(owner).photo(f).caption(cap).build());
            case "video" -> client.execute(SendVideo.builder().chatId(owner).video(f).caption(cap).build());
            case "voice" -> client.execute(SendVoice.builder().chatId(owner).voice(f).caption(cap).build());
            case "audio" -> client.execute(SendAudio.builder().chatId(owner).audio(f).caption(cap).build());
            case "animation" -> client.execute(SendAnimation.builder().chatId(owner).animation(f).caption(cap).build());
            case "document" -> client.execute(SendDocument.builder().chatId(owner).document(f).caption(cap).build());
            case "video_note" -> {
                send(owner, header);
                client.execute(SendVideoNote.builder().chatId(owner).videoNote(f).build());
            }
            case "sticker" -> {
                send(owner, header);
                client.execute(SendSticker.builder().chatId(owner).sticker(f).build());
            }
            default -> send(owner, header + "\n\n(Noma'lum tur)");
        }
    }

    private void send(long chatId, String text) throws Exception {
        client.execute(SendMessage.builder().chatId(chatId).text(text).build());
    }

    // ------------------------------------------------------- yordamchi

    private static String key(String connId, long chatId, int msgId) {
        return connId + ":" + chatId + ":" + msgId;
    }

    private static String nvl(String s) {
        return s == null ? "(bo'sh)" : s;
    }

    private static String name(String first, String last, String username, String title) {
        if (title != null) return title;
        StringBuilder sb = new StringBuilder();
        if (first != null) sb.append(first);
        if (last != null) sb.append(" ").append(last);
        if (username != null) sb.append(" (@").append(username).append(")");
        return sb.length() == 0 ? "Noma'lum" : sb.toString().trim();
    }
}
