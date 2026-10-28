package org.sterl.llmpeon.session;

import java.util.ArrayList;
import java.util.List;

/** UI-independent model of the open session tabs and the active tab. */
public class SessionTabController {

    private final List<SessionId> open = new ArrayList<>();
    private SessionId active;
    private Runnable listener = () -> { };

    public void setListener(Runnable listener) {
        this.listener = listener == null ? () -> { } : listener;
    }

    /** @return true when the tab was not open yet */
    public boolean open(SessionId id) {
        if (id == null || open.contains(id)) return false;
        open.add(id);
        fire();
        return true;
    }

    public boolean close(SessionId id) {
        var index = open.indexOf(id);
        if (index < 0) return false;
        open.remove(index);
        if (id.equals(active)) {
            active = open.isEmpty() ? null : open.get(Math.min(index, open.size() - 1));
        }
        fire();
        return true;
    }

    public void activate(SessionId id) {
        if (id == null || !open.contains(id) || id.equals(active)) return;
        active = id;
        fire();
    }

    public void restore(List<SessionId> ids, SessionId activeId) {
        open.clear();
        if (ids != null) {
            for (var id : ids) {
                if (id != null && !open.contains(id)) open.add(id);
            }
        }
        active = activeId != null && open.contains(activeId)
                ? activeId
                : (open.isEmpty() ? null : open.get(0));
        fire();
    }

    public List<SessionId> openIds() {
        return List.copyOf(open);
    }

    public SessionId active() {
        return active;
    }

    public boolean contains(SessionId id) {
        return open.contains(id);
    }

    private void fire() {
        listener.run();
    }
}
