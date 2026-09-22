package com.alechilles.alecstamework.ui;

/** Page-local navigation state, read and updated on the owning world thread. */
public final class LinkedNpcPanelPageState {
    private int pageSize = 50;
    private int pageIndex;
    private int totalEntries;
    private boolean enabled = true;
    private java.util.List<LinkedNpcEntry> rosterEntries;

    /** External renderers retain their complete snapshot contract. */
    public boolean enabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Detached summaries supply global tab counts without hydrating off-page cards. */
    public void setRosterEntries(java.util.List<LinkedNpcEntry> entries) {
        rosterEntries = java.util.List.copyOf(entries);
    }
    public boolean hasRosterEntries() { return rosterEntries != null; }
    public java.util.List<LinkedNpcEntry> rosterEntries() {
        return rosterEntries == null ? java.util.List.of() : rosterEntries;
    }
    public java.util.List<LinkedNpcEntry> filterRoster(java.util.List<LinkedNpcEntry> entries,
                                                      String state, boolean nearby, String search) {
        return java.util.List.of(CompanionPanelChrome.filter(
                entries.toArray(LinkedNpcEntry[]::new), state, nearby, search));
    }

    public void setPageSize(int size) {
        int bounded = Math.clamp(size, 1, 100);
        if (pageSize != bounded) {
            pageSize = bounded;
            reset();
        }
    }

    public void setTotalEntries(int total) {
        totalEntries = Math.max(0, total);
        pageIndex = Math.min(pageIndex, pageCount() - 1);
    }

    public int pageSize() { return pageSize; }
    public int pageIndex() { return pageIndex; }
    public int totalEntries() { return totalEntries; }
    public int pageCount() { return Math.max(1, (int) ((totalEntries + (long) pageSize - 1) / pageSize)); }
    public int startIndex() { return pageIndex * pageSize; }
    public int endIndex() { return (int) Math.min(totalEntries, startIndex() + (long) pageSize); }

    /** Returns whether navigation changed the visible window. */
    public boolean move(int direction) {
        int next = Math.clamp(pageIndex + (long) Integer.signum(direction), 0, pageCount() - 1);
        if (next == pageIndex) return false;
        pageIndex = next;
        return true;
    }

    public void reset() { pageIndex = 0; }
}
