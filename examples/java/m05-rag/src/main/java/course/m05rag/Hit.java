package course.m05rag;

/** Найденный чанк: id чанка, документ, заголовок секции, текст и дистанция из поиска. */
public record Hit(long id, String docId, String heading, String content, double dist) {
    public Hit(long id, String content) {
        this(id, "", "", content, 0);
    }
}
