package io.ipoli.android.quest.persistence;

import android.support.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.GenericTypeIndicator;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.squareup.otto.Bus;

import org.joda.time.LocalDate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import io.ipoli.android.app.persistence.BaseFirebasePersistenceService;
import io.ipoli.android.app.persistence.FirebaseChildEventListener;
import io.ipoli.android.app.utils.StringUtils;
import io.ipoli.android.quest.data.Quest;
import io.ipoli.android.quest.data.QuestData;
import io.ipoli.android.quest.data.QuestReminder;
import io.ipoli.android.reminder.data.Reminder;

import static io.ipoli.android.app.utils.DateUtils.toStartOfDayUTC;

/**
 * Created by Venelin Valkov <venelin@curiousily.com>
 * on 7/27/16.
 */
public class FirebaseQuestPersistenceService extends BaseFirebasePersistenceService<Quest> implements QuestPersistenceService {

    public FirebaseQuestPersistenceService(Bus eventBus) {
        super(eventBus);
    }

    @Override
    protected GenericTypeIndicator<Map<String, Quest>> getGenericMapIndicator() {
        return new GenericTypeIndicator<Map<String, Quest>>() {
        };
    }

    @Override
    protected Class<Quest> getModelClass() {
        return Quest.class;
    }

    @Override
    protected String getCollectionName() {
        return "quests";
    }

    @Override
    protected DatabaseReference getCollectionReference() {
        return getPlayerReference().child(getCollectionName());
    }

    @Override
    public void listenForInboxQuests(OnDataChangedListener<List<Quest>> listener) {
        listenForListChange(getPlayerReference().child("inboxQuests"), listener);
    }

    @Override
    public void listenForPlannedNonAllDayBetween(LocalDate startDate, LocalDate endDate, OnDataChangedListener<SortedMap<LocalDate, List<Quest>>> listener) {
        String start = String.valueOf(toStartOfDayUTC(startDate).getTime());
        String end = String.valueOf(toStartOfDayUTC(endDate).getTime());
        Query query = getPlayerReference().child("dayQuests")
                .orderByKey()
                .startAt(start)
                .endAt(end);

        GenericTypeIndicator<Map<String, Map<String, Quest>>> typeIndicator = new GenericTypeIndicator<Map<String, Map<String, Quest>>>() {
        };

        ValueEventListener valueListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {

                Map<String, Map<String, Quest>> value = dataSnapshot.getValue(typeIndicator);

                if (value == null) {
                    listener.onDataChanged(new TreeMap<>());
                    return;
                }

                listener.onDataChanged(prepareResult(value));
            }

            @NonNull
            private SortedMap<LocalDate, List<Quest>> prepareResult(Map<String, Map<String, Quest>> value) {
                SortedMap<LocalDate, List<Quest>> result = new TreeMap<>();
                for (Map.Entry<String, Map<String, Quest>> entry : value.entrySet()) {
                    List<Quest> questsForDate = new ArrayList<>(entry.getValue().values());
                    Collections.sort(questsForDate, createDefaultQuestSortQuery()::sort);
                    result.put(new LocalDate(Long.valueOf(entry.getKey())), questsForDate);
                }
                return result;
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        };

        listenForQuery(query, valueListener, listener);
    }

    @Override
    public void findAllCompletedNonAllDayBetween(LocalDate startDate, LocalDate endDate, OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("completedAt").startAt(toStartOfDayUTC(startDate).getTime()).endAt(toStartOfDayUTC(endDate).getTime());
        listenForSingleListChange(query, listener);
    }

    @Override
    public void findAllPlannedAndStarted(OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("scheduled").startAt(toStartOfDayUTC(LocalDate.now()).getTime());
        listenForSingleListChange(query, listener, q -> q.isStarted());
    }

    @Override
    public void findAllIncompleteToDosBefore(LocalDate date, OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("scheduled").endAt(toStartOfDayUTC(date.minusDays(1)).getTime());
        listenForSingleListChange(query, listener, q -> !q.isFromRepeatingQuest() && q.getCompletedAtDate() == null && q.getScheduled() != null);
    }

    @Override
    public void listenForAllNonAllDayForDate(LocalDate currentDate, OnDataChangedListener<List<Quest>> listener) {
        Query query = getPlayerReference().child("dayQuests").child(createDayQuestKey(currentDate));
        listenForListChange(query, listener, null, createDefaultQuestSortQuery());
    }

    @Override
    public void findAllNonAllDayForDate(LocalDate currentDate, OnDataChangedListener<List<Quest>> listener) {
        Query query = getPlayerReference().child("dayQuests").child(createDayQuestKey(currentDate));
        listenForSingleListChange(query, listener, null, createDefaultQuestSortQuery());
    }

    @Override
    public void listenForAllNonAllDayCompletedForDate(LocalDate currentDate, OnDataChangedListener<List<Quest>> listener) {
        Query query = getPlayerReference().child("dayQuests").child(createDayQuestKey(currentDate));
        listenForListChange(query, listener, Quest::isCompleted);
    }

    @Override
    public void listenForAllNonAllDayIncompleteForDate(LocalDate currentDate, OnDataChangedListener<List<Quest>> listener) {
        Query query = getPlayerReference().child("dayQuests").child(createDayQuestKey(currentDate));
        listenForListChange(query, listener, q -> !q.isCompleted(), createDefaultQuestSortQuery());
    }

    @NonNull
    private QuerySort<Quest> createDefaultQuestSortQuery() {
        return (q1, q2) -> {
            if (q1.shouldBeDoneMultipleTimesPerDay() || q2.shouldBeDoneMultipleTimesPerDay()) {
                return Integer.compare(q1.getTimesADay(), q2.getTimesADay());
            }
            int q1Start = q1.getStartMinute();
            if (q1Start < 0) {
                return -1;
            }
            int q2Start = q2.getStartMinute();
            if (q2Start < 0) {
                return 1;
            }
            return Integer.compare(q1Start, q2Start);
        };
    }

    @Override
    public void findAllForRepeatingQuest(String repeatingQuestId, OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("repeatingQuestId").equalTo(repeatingQuestId);
        listenForSingleListChange(query, listener);
    }

    @Override
    public void findAllNotCompletedForRepeatingQuest(String repeatingQuestId, OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("repeatingQuestId").equalTo(repeatingQuestId);
        listenForSingleListChange(query, listener, q -> q.getCompletedAt() == null);
    }

    @Override
    public void findAllUpcomingForRepeatingQuest(LocalDate startDate, String repeatingQuestId, OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("repeatingQuestId").equalTo(repeatingQuestId);
        listenForSingleListChange(query, listener, q -> q.getScheduled() == null || !q.getScheduledDate().before(toStartOfDayUTC(startDate)));
    }

    @Override
    public void countAllCompletedWithPriorityForDate(int priority, LocalDate date, OnDataChangedListener<Long> listener) {
        Query query = getCollectionReference().orderByChild("scheduled").equalTo(toStartOfDayUTC(date).getTime());
        listenForSingleCountChange(query, listener, q -> q.getCompletedAtDate() != null && q.getPriority() == priority);
    }

    @Override
    public void findQuestRemindersAtStartTime(long startTime, OnDataChangedListener<List<QuestReminder>> listener) {
        Query query = getPlayerReference().child("questReminders").child(String.valueOf(startTime));
        query.addListenerForSingleValueEvent(new ValueEventListener() {

            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    listener.onDataChanged(new ArrayList<>());
                    return;
                }
                GenericTypeIndicator<Map<String, QuestReminder>> indicator = new GenericTypeIndicator<Map<String, QuestReminder>>() {
                };
                Map<String, QuestReminder> remindersMap = dataSnapshot.getValue(indicator);
                listener.onDataChanged(new ArrayList<>(remindersMap.values()));
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    @Override
    public void findNextReminderTime(OnDataChangedListener<Long> listener) {
        Query query = getPlayerReference().child("questReminders").orderByKey().startAt(String.valueOf(new Date().getTime())).limitToFirst(1);
        query.addListenerForSingleValueEvent(new ValueEventListener() {

            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    listener.onDataChanged(null);
                    return;
                }
                Map<String, Object> value = (Map<String, Object>) dataSnapshot.getValue();
                listener.onDataChanged(Long.valueOf(value.keySet().iterator().next()));
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    @Override
    public void listenForAllIncompleteOrMostImportantForDate(LocalDate date, OnDataChangedListener<List<Quest>> listener) {
        Query query = getCollectionReference().orderByChild("scheduled").equalTo(toStartOfDayUTC(date).getTime());
        listenForListChange(query, listener,
                q -> !q.isAllDay() && (q.getCompletedAtDate() == null || q.getPriority() == Quest.PRIORITY_MOST_IMPORTANT_FOR_DAY),
                (q1, q2) -> Integer.compare(q1.getStartMinute(), q2.getStartMinute()));
    }

    @Override
    public void findIncompleteNotRepeatingNotForChallenge(String searchText, String challengeId, OnDataChangedListener<List<Quest>> listener) {
        listenForSingleListChange(getCollectionReference(), listener, q -> !challengeId.equals(q.getChallengeId()) &&
                q.getCompletedAtDate() == null &&
                !q.isFromRepeatingQuest() &&
                q.getName().toLowerCase().contains(searchText.toLowerCase())
        );
    }

    @Override
    public void listenForIncompleteNotRepeating(OnDataChangedListener<List<Quest>> listener) {
        listenForListChange(getCollectionReference(), listener, q -> q.getCompletedAtDate() == null &&
                !q.isFromRepeatingQuest()
        );
    }

    @Override
    public void listenForReminderChange(OnChangeListener onChangeListener) {
        Query query = getPlayerReference().child("questReminders");
        FirebaseChildEventListener.listenForChanges(onChangeListener, query, childListeners);
    }

    @Override
    public void deleteRemindersAtTime(long startTime) {
        getPlayerReference().child("questReminders").child(String.valueOf(startTime)).setValue(null);
    }

    @Override
    public void save(Quest quest) {
        Map<String, Object> data = new HashMap<>();
        populateNewQuestData(quest, data);
        getPlayerReference().updateChildren(data);
    }

    @Override
    public void populateNewQuestData(Quest quest, Map<String, Object> data) {
        DatabaseReference questRef = getCollectionReference().push();
        quest.setId(questRef.getKey());
        if (quest.isCompleted()) {
            addDayQuest(quest, data);
        } else if (shouldMoveToInbox(quest)) {
            data.put("/inboxQuests/" + quest.getId(), quest);
        } else {
            quest.setPreviousScheduledDate(quest.getScheduled());

            addDayQuest(quest, data);

            if (shouldAddQuestReminders(quest)) {
                addQuestReminders(quest, data);
            }
        }
        if (!StringUtils.isEmpty(quest.getChallengeId())) {
            data.put("/challenges/" + quest.getChallengeId() + "/questsData/" + quest.getId(), new QuestData(quest));
            if (StringUtils.isEmpty(quest.getRepeatingQuestId())) {
                data.put("/challenges/" + quest.getChallengeId() + "/challengeQuests/" + quest.getId(), quest);
            }
        }

        data.put("/quests/" + quest.getId(), quest);
    }

    @Override
    public void populateDeleteQuestData(Quest quest, Map<String, Object> data) {
        populateDeleteQuestDataFromRepeatingQuest(quest, data);
        if (quest.isFromRepeatingQuest()) {
            data.put("/repeatingQuests/" + quest.getRepeatingQuestId() + "/questsData/" + quest.getId(), null);
        }
    }


    @Override
    public void populateDeleteQuestDataFromRepeatingQuest(Quest quest, Map<String, Object> data) {
        data.put("/inboxQuests/" + quest.getId(), null);
        if (quest.getScheduled() != null) {
            data.put("/dayQuests/" + quest.getScheduled() + "/" + quest.getId(), null);
        }

        for (Reminder reminder : quest.getReminders()) {
            data.put("/questReminders/" + String.valueOf(reminder.getStart()) + "/" + quest.getId(), null);
        }

        if (quest.isFromChallenge()) {
            data.put("/challenges/" + quest.getChallengeId() + "/questsData/" + quest.getId(), null);
            data.put("/challenges/" + quest.getChallengeId() + "/challengeQuests/" + quest.getId(), null);
        }

        data.put("/quests/" + quest.getId(), null);
    }

    @Override
    public void save(List<Quest> quests) {
        Map<String, Object> data = new HashMap<>();
        for (Quest quest : quests) {
            populateNewQuestData(quest, data);
        }
        getPlayerReference().updateChildren(data);
    }

    @Override
    public void update(List<Quest> quests) {
        Map<String, Object> data = new HashMap<>();
        for (Quest quest : quests) {
            populateUpdateQuest(quest, data);
        }
        getPlayerReference().updateChildren(data);
    }

    @Override
    public void listenForDayQuestChange(LocalDate date, OnChangeListener onChangeListener) {
        Query query = getPlayerReference().child("dayQuests").child(createDayQuestKey(date));
        FirebaseChildEventListener.listenForChanges(onChangeListener, query, childListeners);
    }

    @NonNull
    private String createDayQuestKey(LocalDate date) {
        return String.valueOf(toStartOfDayUTC(date).getTime());
    }

    private boolean shouldAddQuestReminders(Quest quest) {
        return !quest.isCompleted() && quest.isScheduled() && !quest.getReminders().isEmpty() && !quest.isStarted();
    }

    @Override
    public void delete(Quest quest) {
        Map<String, Object> data = new HashMap<>();
        populateDeleteQuestData(quest, data);
        getPlayerReference().updateChildren(data);
    }

    @Override
    public void update(Quest quest) {
        Map<String, Object> data = new HashMap<>();

        populateUpdateQuest(quest, data);
        getPlayerReference().updateChildren(data);
    }

    private void populateUpdateQuest(Quest quest, Map<String, Object> data) {
        Long lastScheduled = quest.getPreviousScheduledDate();

        // remove old day quest
        if (lastScheduled != null) {
            removeOldScheduledDate(quest, data, lastScheduled);
        }

        if (shouldMoveToInbox(quest)) {

            // add inbox
            data.put("/inboxQuests/" + quest.getId(), quest);

        } else {

            // remove inbox
            data.put("/inboxQuests/" + quest.getId(), null);

            addDayQuest(quest, data);
        }

        // remove reminders
        removeOldReminders(quest, data);

        quest.setReminderStartTimes(new ArrayList<>());
        if (shouldAddQuestReminders(quest)) {
            addQuestReminders(quest, data);
        }

        if (quest.getPreviousChallengeId() != null) {
            String challengeId = quest.getPreviousChallengeId();
            data.put("/challenges/" + challengeId + "/questsData/" + quest.getId(), null);
            data.put("/challenges/" + challengeId + "/challengeQuests/" + quest.getId(), null);
        }

        if (quest.getChallengeId() != null) {
            String challengeId = quest.getChallengeId();
            data.put("/challenges/" + challengeId + "/questsData/" + quest.getId(), new QuestData(quest));
            data.put("/challenges/" + challengeId + "/challengeQuests/" + quest.getId(), quest);
        }

        if (quest.isFromRepeatingQuest()) {
            String repeatingQuestId = quest.getRepeatingQuestId();
            data.put("/repeatingQuests/" + repeatingQuestId + "/questsData/" + quest.getId(), new QuestData(quest));
        }

        quest.setPreviousScheduledDate(quest.getScheduled());
        data.put("/quests/" + quest.getId(), quest);
    }

    private void addQuestReminders(Quest quest, Map<String, Object> data) {
        for (Reminder reminder : quest.getReminders()) {
            reminder.calculateStartTime(quest);
            quest.addReminderStartTime(reminder.getStart());
            data.put("/questReminders/" + reminder.getStart() + "/" + quest.getId(), new QuestReminder(quest, reminder));
        }
    }

    private void addDayQuest(Quest quest, Map<String, Object> data) {
        data.put("/dayQuests/" + quest.getScheduled() + "/" + quest.getId(), quest);
    }

    private void removeOldReminders(Quest quest, Map<String, Object> data) {
        for (long startTime : quest.getReminderStartTimes()) {
            data.put("/questReminders/" + String.valueOf(startTime) + "/" + quest.getId(), null);
        }
    }

    private boolean shouldMoveToInbox(Quest quest) {
        return quest.getScheduled() == null;
    }

    private void removeOldScheduledDate(Quest quest, Map<String, Object> data, long lastScheduledDate) {
        data.put("/dayQuests/" + lastScheduledDate + "/" + quest.getId(), null);
    }
}
