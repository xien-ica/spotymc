package xien.jxsh.spotymc.gui.model;

/** A row of content for the left panel's list, independent of whether it's a search or library row. */
public record RowEntry(String label, String uri, RowKind kind, int index) {
	public RowEntry(String label, String uri, RowKind kind) {
		this(label, uri, kind, -1);
	}
}