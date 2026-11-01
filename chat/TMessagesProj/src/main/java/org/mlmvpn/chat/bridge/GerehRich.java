package org.mlmvpn.chat.bridge;

import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

import java.util.ArrayList;
import java.util.List;

/**
 * MLMCHAT: «مقاله», Telegram's rich editor (Ehsan, 28 Oct 2026, m7: it asked for Premium, and what it
 * sent got a «!»). Its blocks travel beside an empty text, a form Gereh's messages do not have; so an
 * article goes as that text with its formatting (SPEC 5 `Text` and its entities), which every Gereh
 * phone shows: headings bold, lists with their marks, quotes as quotes, code as code, links as
 * links. What a text cannot hold (pictures, video, maps, tables) is left out.
 */
final class GerehRich {

    private GerehRich() {
    }

    /** A send that carries an article gets its text and formatting from it: it then goes as any text. */
    static void flatten(TLRPC.TL_messages_sendMessage q) {
        if (q.rich_message == null) {
            return;
        }
        Builder b = new Builder();
        for (TL_iv.PageBlock block : q.rich_message.blocks) {
            b.block(block);
        }
        q.message = b.text();
        q.entities = b.entities(q.message.length());
        if (!q.entities.isEmpty()) {
            q.flags |= 8;
        }
        q.rich_message = null;
    }

    private static final class Builder {
        private final StringBuilder sb = new StringBuilder();
        private final List<TLRPC.MessageEntity> marks = new ArrayList<>();

        String text() {
            int end = sb.length();
            while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) {
                end--;
            }
            return sb.substring(0, end);
        }

        /** The marks inside the text that was kept (a trailing blank cut off shortens the last ones). */
        ArrayList<TLRPC.MessageEntity> entities(int length) {
            ArrayList<TLRPC.MessageEntity> out = new ArrayList<>();
            for (TLRPC.MessageEntity e : marks) {
                if (e.offset < length) {
                    e.length = Math.min(e.length, length - e.offset);
                    if (e.length > 0) {
                        out.add(e);
                    }
                }
            }
            return out;
        }

        void block(TL_iv.PageBlock bl) {
            if (bl == null) {
                return;
            }
            int start = sb.length();
            if (bl instanceof TL_iv.pageBlockTitle || bl instanceof TL_iv.pageBlockSubtitle || bl instanceof TL_iv.pageBlockHeader
                    || bl instanceof TL_iv.pageBlockSubheader || bl instanceof TL_iv.pageBlockKicker) {
                rich(bl.text);
                mark(new TLRPC.TL_messageEntityBold(), start);
                end();
            } else if (bl instanceof TL_iv.pageBlockParagraph) {
                rich(bl.text);
                end();
            } else if (bl instanceof TL_iv.pageBlockPreformatted) {
                rich(bl.text);
                TLRPC.TL_messageEntityPre pre = new TLRPC.TL_messageEntityPre();
                pre.language = ((TL_iv.pageBlockPreformatted) bl).language != null ? ((TL_iv.pageBlockPreformatted) bl).language : "";
                mark(pre, start);
                end();
            } else if (bl instanceof TL_iv.pageBlockFooter) {
                rich(bl.text);
                mark(new TLRPC.TL_messageEntityItalic(), start);
                end();
            } else if (bl instanceof TL_iv.pageBlockDivider) {
                sb.append("———");
                end();
            } else if (bl instanceof TL_iv.pageBlockBlockquote || bl instanceof TL_iv.pageBlockPullquote) {
                rich(bl.text);
                mark(new TLRPC.TL_messageEntityBlockquote(), start);
                end();
            } else if (bl instanceof TL_iv.pageBlockBlockquoteBlocks) {
                for (TL_iv.PageBlock inner : ((TL_iv.pageBlockBlockquoteBlocks) bl).blocks) {
                    block(inner);
                }
                trimEnd();
                mark(new TLRPC.TL_messageEntityBlockquote(), start);
                end();
            } else if (bl instanceof TL_iv.pageBlockList) {
                for (TL_iv.PageListItem item : ((TL_iv.pageBlockList) bl).items) {
                    sb.append(item.checkbox ? (item.checked ? "☑ " : "☐ ") : "• ");
                    if (item instanceof TL_iv.TL_pageListItemText) {
                        rich(((TL_iv.TL_pageListItemText) item).text);
                    } else if (item instanceof TL_iv.TL_pageListItemBlocks) {
                        inline(((TL_iv.TL_pageListItemBlocks) item).blocks);
                    }
                    sb.append('\n');
                }
                end();
            } else if (bl instanceof TL_iv.pageBlockOrderedList) {
                TL_iv.pageBlockOrderedList list = (TL_iv.pageBlockOrderedList) bl;
                int n = list.start > 0 ? list.start : 1;
                for (TL_iv.PageListOrderedItem item : list.items) {
                    sb.append(item.num != null && !item.num.isEmpty() ? item.num : String.valueOf(n)).append(". ");
                    if (item instanceof TL_iv.TL_pageListOrderedItemText) {
                        rich(((TL_iv.TL_pageListOrderedItemText) item).text);
                    } else if (item instanceof TL_iv.TL_pageListOrderedItemBlocks) {
                        inline(((TL_iv.TL_pageListOrderedItemBlocks) item).blocks);
                    }
                    sb.append('\n');
                    n++;
                }
                end();
            } else if (bl instanceof TL_iv.pageBlockDetails) {
                TL_iv.pageBlockDetails d = (TL_iv.pageBlockDetails) bl;
                rich(d.title);
                mark(new TLRPC.TL_messageEntityBold(), start);
                sb.append('\n');
                for (TL_iv.PageBlock inner : d.blocks) {
                    block(inner);
                }
            }
            // pictures, video, embeds, maps, tables, buttons: no place for them in a text
        }

        /** A list item made of blocks, on its own line. */
        private void inline(List<TL_iv.PageBlock> blocks) {
            int before = sb.length();
            for (TL_iv.PageBlock inner : blocks) {
                block(inner);
            }
            if (sb.length() > before) {
                trimEnd();
            }
        }

        private void rich(TL_iv.RichText t) {
            if (t == null || t instanceof TL_iv.textEmpty) {
                return;
            }
            if (t instanceof TL_iv.textPlain) {
                String s = ((TL_iv.textPlain) t).text;
                if (s != null) {
                    sb.append(s);
                }
                return;
            }
            if (t instanceof TL_iv.textConcat) {
                for (TL_iv.RichText part : t.texts) {
                    rich(part);
                }
                return;
            }
            int start = sb.length();
            rich(t.text);
            TLRPC.MessageEntity e = null;
            if (t instanceof TL_iv.textBold || t instanceof TL_iv.textMarked) {
                e = new TLRPC.TL_messageEntityBold();
            } else if (t instanceof TL_iv.textItalic) {
                e = new TLRPC.TL_messageEntityItalic();
            } else if (t instanceof TL_iv.textUnderline) {
                e = new TLRPC.TL_messageEntityUnderline();
            } else if (t instanceof TL_iv.textStrike) {
                e = new TLRPC.TL_messageEntityStrike();
            } else if (t instanceof TL_iv.textFixed) {
                e = new TLRPC.TL_messageEntityCode();
            } else if (t instanceof TL_iv.textSpoiler) {
                e = new TLRPC.TL_messageEntitySpoiler();
            } else if (t instanceof TL_iv.textUrl && t.url != null && !t.url.isEmpty()) {
                TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
                link.url = t.url;
                e = link;
            }
            if (e != null) {
                mark(e, start);
            }
        }

        private void mark(TLRPC.MessageEntity e, int start) {
            e.offset = start;
            e.length = sb.length() - start;
            if (e.length > 0) {
                marks.add(e);
            }
        }

        /** A block ends with an empty line before the next. */
        private void end() {
            trimEnd();
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
        }

        private void trimEnd() {
            int end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == '\n') {
                end--;
            }
            sb.setLength(end);
        }
    }
}
