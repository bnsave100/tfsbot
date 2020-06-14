package services;

import com.typesafe.config.Config;
import model.*;
import model.telegram.ContentType;
import model.telegram.ParseMode;
import utils.LangMap;
import utils.Strings;
import utils.TFileFactory;

import javax.inject.Inject;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static utils.LangMap.v;
import static utils.TextUtils.*;

/**
 * @author Denis Danilin | denis@danilin.name
 * 16.05.2020
 * tfs ☭ sweat and blood
 */
@SuppressWarnings("unchecked")
public class HeadQuarters {
    private enum ViewKind {subjectShares, gearSubject, none, viewDir, viewFile, viewLabel, viewSearchedDir, viewSearchedFile, viewSearchedLabel, searchResults}

    private static final Comparator<TFile> sorter = (o1, o2) -> {
        final int res = Boolean.compare(o2.isDir(), o1.isDir());
        return res != 0 ? res : o1.getName().compareTo(o2.getName());
    };

    @Inject
    private Config config;

    @Inject
    private TfsService fsService;

    @Inject
    private UserService userService;

    @Inject
    private TgApi api;

    public void doCommand(final Command command, final User user) {
        TFile subject = fsService.get(user.getSubjectId(), user), parent = subject.getParentId() == null ? subject : fsService.get(subject.getParentId(), user);
        ViewKind viewKind = ViewKind.none;

        switch (command.type) {
            case backToSearch:
                subject = fsService.get(user.getSearchDirId(), user);
                parent = subject.getParentId() == null ? subject : fsService.get(subject.getParentId(), user);
                viewKind = ViewKind.searchResults;
                break;
            case cancelShare:
            case cancelSearch:
                viewKind = subject.isDir() ? ViewKind.viewDir : subject.isLabel() ? ViewKind.viewLabel : ViewKind.viewFile;
                user.resetState();
                break;
            case changeRo:
                fsService.changeShareRo(((Share) byIdx(command.elementIdx, subject, user)).getId(), user);
                viewKind = ViewKind.subjectShares;
                break;
            case doSearch:
                user.setQuery(command.input);
                user.setViewOffset(0);
                user.resetState();
                user.setSearching();
                if (!subject.isDir()) {
                    user.setSubjectId(user.getRootId());
                    subject = parent = fsService.findRoot(user.getId());
                }
                user.setSearchDirId(subject.getId());
                viewKind = ViewKind.searchResults;
                break;
            case dropFile:
            case dropDir:
            case dropLabel: // может объединить?
                fsService.rm(subject, user);
                user.setSubjectId(parent.getId());
                user.setViewOffset(0);
                user.resetState();
                subject = parent;
                parent = subject.getParentId() == null ? subject : fsService.get(subject.getParentId(), user);
                viewKind = ViewKind.viewDir;
                break;
            case dropGlobLink:
                fsService.dropGlobalShareByEntry(subject.getId(), user);
                if (subject.isShared() && fsService.noSharesExist(subject.getId(), user)) {
                    subject.setUnshared();
                    fsService.updateMeta(subject, user);
                }
                viewKind = ViewKind.subjectShares;
                break;
            case dropShare:
                fsService.dropShare(((Share) byIdx(command.elementIdx, subject, user)).getId(), user);

                if (fsService.noSharesExist(subject.getId(), user)) {
                    subject.setUnshared();
                    fsService.updateMeta(subject, user);
                }
                viewKind = ViewKind.subjectShares;
                break;
            case editLabel:
                if (!subject.getName().equals(command.input) && fsService.entryMissed(command.input, user)) {
                    subject.setName(command.input);
                    subject.setPath(Paths.get(parent.getPath()).resolve(command.input).toString());
                    fsService.updateMeta(subject, user);
                }
                user.resetState();
                viewKind = ViewKind.viewLabel;
                break;
            case rewind:
            case forward:
                user.deltaSearchOffset(command.type == CommandType.rewind ? -10 : 10);
                viewKind = user.isSearching() ? ViewKind.searchResults : ViewKind.viewDir;
                break;
            case gear:
                user.setGearing();
                viewKind = ViewKind.gearSubject;
                break;
            case openParent:
                user.resetState();
                user.setSubjectId(parent.getId());
                user.setViewOffset(0);
                subject = parent;
                parent = subject.getParentId() == null ? subject : fsService.get(subject.getParentId(), user);
                viewKind = ViewKind.viewDir;
                break;
            case joinPublicShare:
                final Share share;
                if (!isEmpty(command.input) && (share = fsService.getPublicShare(command.input)) != null && share.getOwner() != user.getId()) {
                    final TFile dir = fsService.applyShareByLink(share, user);

                    if (dir != null) {
                        user.setSubjectId(dir.getId());
                        user.setViewOffset(0);
                        subject = dir;
                        parent = fsService.get(subject.getParentId(), user);
                    }
                }
                user.resetState();
                viewKind = ViewKind.viewDir;
                break;
            case makeGlobLink:
                fsService.makeShare(subject.getName(), user, subject.getId(), 0, null);

                if (!subject.isShared()) {
                    subject.setShared();
                    fsService.updateMeta(subject, user);
                }
                viewKind = ViewKind.subjectShares;
                break;
            case mkDir:
                user.resetState();
                if (!isEmpty(command.input)) {
                    if (fsService.entryMissed(command.input, user)) {
                        final TFile n = fsService.mk(TFileFactory.dir(command.input, subject.getId(), user.getId()));

                        if (n != null) {
                            parent = subject;
                            subject = n;
                            user.setViewOffset(0);
                            user.setSubjectId(subject.getId());
                            user.resetState();
                        }
                    }
                } else {
                    api.dialog(LangMap.Value.TYPE_FOLDER, user);
                    user.setWaitDirInput();
                    viewKind = ViewKind.none;
                }
                break;
            case mkGrant:
                api.dialog(subject.isDir() ? LangMap.Value.SEND_CONTACT_DIR : LangMap.Value.SEND_CONTACT_FILE, user, subject.getName());
                user.setWaitFileGranting();
                viewKind = ViewKind.none;
                break;
            case grantAccess:
                final User contact = new User();
                contact.setId(command.file.getOwner());
                contact.name = command.file.getName();
                contact.setLang(user.getLang());

                final User target = userService.resolveUser(contact);

                if (!fsService.shareExist(subject.getId(), target.getId(), user)) {
                    fsService.makeShare(command.file.getName(), user, subject.getId(), target.getId(), notNull(target.getLang(), "en"));

                    if (!subject.isShared()) {
                        subject.setShared();
                        fsService.updateMeta(subject, user);
                    }
                }
                viewKind = ViewKind.subjectShares;
                break;
            case mkLabel:
                user.resetState();
                if (!isEmpty(command.input)) {
                    if (fsService.entryMissed(command.input, user))
                        fsService.mk(TFileFactory.label(command.input, subject.getId(), user.getId()));
                } else {
                    api.dialog(LangMap.Value.TYPE_LABEL, user);
                    user.setWaitLabelInput();
                    viewKind = ViewKind.none;
                }
                break;
            case openDir:
                user.resetState();
                final TFile dir = byIdx(command.elementIdx, subject, user);
                parent = subject;
                subject = dir;
                user.setViewOffset(0);
                user.setSubjectId(subject.getId());
                viewKind = ViewKind.viewDir;
                break;
            case openFile:
                user.resetState();
                final TFile file = byIdx(command.elementIdx, subject, user);
                parent = subject;
                subject = file;
                user.setSubjectId(subject.getId());
                viewKind = ViewKind.viewFile;
                break;
            case openLabel:
                final TFile label = byIdx(command.elementIdx, subject, user);
                user.resetState();
                parent = subject;
                subject = label;
                user.setSubjectId(subject.getId());
                viewKind = ViewKind.viewLabel;
                break;
            case openSearchedDir:
                final TFile sd = byIdx(command.elementIdx, subject, user);
                parent = subject;
                subject = sd;
                user.setViewOffset(0);
                user.setSubjectId(subject.getId());
                viewKind = ViewKind.viewSearchedDir;
                break;
            case openSearchedFile:
                final TFile sf = byIdx(command.elementIdx, subject, user);
                parent = subject;
                subject = sf;
                user.setSubjectId(subject.getId());
                viewKind = ViewKind.viewSearchedFile;
                break;
            case openSearchedLabel:
                final TFile sl = byIdx(command.elementIdx, subject, user);
                parent = subject;
                subject = sl;
                user.setSubjectId(subject.getId());
                viewKind = ViewKind.viewSearchedLabel;
                break;
            case renameDir:
                user.resetState();
                if (!subject.getName().equals(command.input) && fsService.entryMissed(command.input, user)) {
                    subject.setName(command.input);
                    subject.setPath(Paths.get(parent.getPath()).resolve(command.input).toString());
                    fsService.updateMeta(subject, user);
                }
                viewKind = ViewKind.viewDir;
                break;
            case renameFile:
                user.resetState();
                if (!subject.getName().equals(command.input) && fsService.entryMissed(command.input, user)) {
                    subject.setName(command.input);
                    subject.setPath(Paths.get(parent.getPath()).resolve(command.input).toString());
                    fsService.updateMeta(subject, user);
                }
                viewKind = ViewKind.viewFile;
                break;
            case resetToRoot:
                user.resetState();
                user.resetInputWait();
                user.setSubjectId(user.getRootId());
                subject = parent = fsService.findRoot(user.getId());
                viewKind = ViewKind.viewDir;
                break;
            case share:
                user.resetState();
                user.setSharing();
                viewKind = ViewKind.subjectShares;
                break;
            case uploadFile:
                command.file.setParentId(subject.isDir() ? subject.getId() : parent.getId());
                command.file.setOwner(user.getId());

                fsService.mk(command.file);

                viewKind = subject.isDir() ? ViewKind.viewDir : ViewKind.none;
                break;
            case contextHelp:
                doHelp(subject, user);
                break;
        }

        if (viewKind != ViewKind.none)
            doView(subject, parent, viewKind, user);

        userService.update(user);
    }

    private void doHelp(final TFile subject, final User user) {
        if (user.isSearching())
            api.dialogUnescaped(LangMap.Value.SEARCHED_HELP, user, TgApi.voidKbd);
        else if (user.isSharing())
            api.dialogUnescaped(subject.isDir() ? LangMap.Value.SHARE_DIR_HELP : LangMap.Value.SHARE_FILE_HELP, user, TgApi.voidKbd);
        else if (user.isGearing() && !user.isOnTop())
            api.dialogUnescaped(LangMap.Value.GEAR_HELP, user, TgApi.voidKbd);
        else if (subject.isLabel())
            api.dialogUnescaped(LangMap.Value.LABEL_HELP, user, TgApi.voidKbd);
        else if (subject.isFile())
            api.dialogUnescaped(LangMap.Value.FILE_HELP, user, TgApi.voidKbd);
        else if (user.isOnTop())
            api.dialogUnescaped(LangMap.Value.ROOT_HELP, user, TgApi.voidKbd);
        else
            api.dialogUnescaped(LangMap.Value.LS_HELP, user, TgApi.voidKbd);
    }

    private void doView(final TFile subject, final TFile parent, final ViewKind viewKind, final User user) {
        final TgApi.Keyboard kbd = new TgApi.Keyboard();
        final StringBuilder body = new StringBuilder(16);
        TFile file = null;
        String format = ParseMode.md2;

        try {
            switch (viewKind) {
                case gearSubject: {
                    final List<TFile> scope = scope(subject, user);
                    body.append(escapeMd(v(LangMap.Value.GEARING, user, notNull(subject.getPath(), "/"))));

                    if (!user.isOnTop()) {
                        kbd.button(CommandType.share.b());
                        kbd.button(CommandType.renameDir.b());
                        kbd.button(CommandType.dropDir.b());
                    }

                    kbd.button(CommandType.cancelShare.b());

                    for (int i = 0; i < scope.size(); i++) {
                        kbd.newLine();
                        kbd.button(scope.get(i).toButton(i));
                    }
                }
                break;
                case searchResults: {
                    final List<TFile> scope = scope(subject, user);

                    body.append(escapeMd(v(LangMap.Value.SEARCHED, user, user.getQuery(), notNull(subject.getPath(), "/"))));
                    kbd.button(CommandType.cancelSearch.b());

                    if (scope.isEmpty())
                        body.append("\n_").append(v(LangMap.Value.NO_RESULTS, user)).append("_");
                    else {
                        body.append("\n_").append(escapeMd(v(LangMap.Value.RESULTS_FOUND, user, scope.size()))).append("_");

                        final List<TFile> sorted = scope.stream().sorted(sorter).collect(Collectors.toList());
                        final int skip = notNull(subject.getPath(), "/").length();

                        for (int i = user.getViewOffset(); i < Math.min(user.getViewOffset() + 10, sorted.size()); i++) {
                            kbd.newLine();
                            kbd.button(sorted.get(i).toSearchedButton(skip, scope.indexOf(sorted.get(i))));
                        }

                        if (user.getViewOffset() > 0 || scope.stream().filter(e -> e.getType() != ContentType.LABEL).count() > 10) {
                            kbd.newLine();

                            if (user.getViewOffset() > 0)
                                kbd.button(CommandType.rewind.b());
                            if (scope.stream().filter(e -> e.getType() != ContentType.LABEL).count() > 10)
                                kbd.button(CommandType.forward.b());
                        }
                    }
                }
                break;
                case subjectShares: {
                    final List<Share> scope = subject.isShared() ? scope(subject, user) : Collections.emptyList();
                    final Share glob = scope.stream().filter(s -> s.getSharedTo() == 0).findAny().orElse(null);
                    final long countPers = scope.stream().filter(s -> s.getSharedTo() > 0).count();

                    body.append(v(subject.isDir() ? LangMap.Value.DIR_ACCESS : LangMap.Value.FILE_ACCESS, user, "*" + escapeMd(subject.getPath()) + "*"))
                            .append("\n\n")
                            .append(Strings.Uni.link).append(": ").append("_")
                            .append(escapeMd((glob != null ? "https://t.me/" + config.getString("service.bot.nick") + "?start=shared-" + glob.getId() :
                                    v(LangMap.Value.NO_GLOBAL_LINK, user)))).append("_\n");

                    if (countPers <= 0)
                        body.append(Strings.Uni.People + ": _").append(escapeMd(v(LangMap.Value.NO_PERSONAL_GRANTS, user))).append("_");

                    kbd.button(glob != null ? CommandType.dropGlobLink.b() : CommandType.makeGlobLink.b());
                    kbd.button(CommandType.mkGrant.b());
                    kbd.button(CommandType.cancelShare.b());

                    final AtomicInteger counter = new AtomicInteger(0);

                    scope.stream()
                            .filter(s -> !s.isGlobal())
                            .sorted(Comparator.comparing(Share::getName))
                            .forEach(s -> {
                                kbd.newLine();
                                kbd.button(CommandType.changeRo.b(v(s.isReadWrite() ? LangMap.Value.SHARE_RW : LangMap.Value.SHARE_RO, user, s.getName()), counter.get()));
                                kbd.button(CommandType.dropShare.b(counter.getAndIncrement()));
                            });
                }
                break;
                case viewDir: {
                    final List<TFile> scope = scope(subject, user);
                    body.append(notNull(escapeMd(subject.getPath()), "/"));

                    final StringBuilder ls = new StringBuilder();
                    scope.stream().filter(TFile::isLabel).sorted(Comparator.comparing(TFile::getName)).forEach(l -> ls.append('\n').append("```\n").append(escapeMd(l.name)).append("```\n"));

                    if (ls.length() > 0)
                        body.append(ls.toString());
                    else if (scope.isEmpty())
                        body.append("\n_").append(escapeMd(v(LangMap.Value.NO_CONTENT, user))).append("_");

                    if (!user.isOnTop())
                        kbd.button(CommandType.openParent.b());
                    kbd.button(CommandType.mkLabel.b());
                    kbd.button(CommandType.mkDir.b());
                    kbd.button(CommandType.gear.b());

                    final List<TFile> toButtons = scope.stream().sorted(sorter).filter(e -> e.getType() != ContentType.LABEL)
                            .collect(Collectors.toList());

                    for (int i = user.getViewOffset(); i < Math.min(user.getViewOffset() + 10, toButtons.size()); i++) {
                        kbd.newLine();
                        kbd.button(toButtons.get(i).toButton(scope.indexOf(toButtons.get(i))));
                    }

                    if (user.getViewOffset() > 0 || scope.stream().filter(e -> e.getType() != ContentType.LABEL).count() > 10) {
                        kbd.newLine();

                        if (user.getViewOffset() > 0)
                            kbd.button(CommandType.rewind.b());
                        if (scope.stream().filter(e -> e.getType() != ContentType.LABEL).count() > 10)
                            kbd.button(CommandType.forward.b());
                    }
                }
                break;
                case viewFile: {
                    file = subject;

                    body.append(notNull(escapeMd(file.getPath()), "/"));

                    kbd.button(CommandType.openParent.b(), CommandType.share.b(), CommandType.renameFile.b(), CommandType.dropFile.b());
                }
                break;
                case viewLabel: {
                    body.append('*').append(notNull(escapeMd(parent.getPath()), "/")).append("*\n\n").append(escapeMd(subject.name));

                    kbd.button(CommandType.openParent.b(), CommandType.editLabel.b(), CommandType.dropLabel.b());
                }
                break;
                case viewSearchedDir: {
                    final List<TFile> scope = fsService.list(subject.getId(), user);

                    body.append("_").append(escapeMd(v(LangMap.Value.SEARCHED, user, user.getQuery(), parent.getPath()))).append("_\n");
                    body.append(escapeMd(subject.getName()));

                    final StringBuilder ls = new StringBuilder();
                    scope.stream().filter(TFile::isLabel).sorted(Comparator.comparing(TFile::getName)).forEach(l -> ls.append('\n').append("```\n").append(escapeMd(l.name)).append("```\n"));

                    if (ls.length() > 0)
                        body.append(ls.toString());
                    else if (scope.isEmpty())
                        body.append("\n_").append(escapeMd(v(LangMap.Value.NO_CONTENT, user))).append("_");

                    kbd.button(CommandType.backToSearch.b());
                    kbd.button(CommandType.mkLabel.b());
                    kbd.button(CommandType.mkDir.b());
                    kbd.button(CommandType.gear.b());

                    final List<TFile> toButtons = scope.stream().sorted(sorter).filter(e -> e.getType() != ContentType.LABEL)
                            .collect(Collectors.toList());

                    for (int i = user.getViewOffset(); i < Math.min(user.getViewOffset() + 10, toButtons.size()); i++) {
                        kbd.newLine();
                        kbd.button(toButtons.get(i).toButton(scope.indexOf(toButtons.get(i))));
                    }

                    if (user.getViewOffset() > 0 || scope.stream().filter(e -> e.getType() != ContentType.LABEL).count() > 10) {
                        kbd.newLine();

                        if (user.getViewOffset() > 0)
                            kbd.button(CommandType.rewind.b());
                        if (scope.stream().filter(e -> e.getType() != ContentType.LABEL).count() > 10)
                            kbd.button(CommandType.forward.b());
                    }
                }
                break;
                case viewSearchedFile: {
                    file = subject;

                    body.append("_").append(escapeMd(v(LangMap.Value.SEARCHED, user, user.getQuery(), parent.getPath()))).append("_\n");
                    body.append(escapeMd(subject.getName()));

                    kbd.button(CommandType.backToSearch.b(), CommandType.share.b(), CommandType.renameFile.b(), CommandType.dropFile.b());
                }
                break;
                case viewSearchedLabel: {
                    body.append("_").append(escapeMd(v(LangMap.Value.SEARCHED, user, user.getQuery(), parent.getPath()))).append("_\n");
                    body.append("```\n").append(subject.name).append("\n```");

                    kbd.button(CommandType.backToSearch.b(), CommandType.editLabel.b(), CommandType.dropLabel.b());
                }
                break;
            }
        } finally {
            api.sendContent(file, body.toString(), format, kbd, user);
        }
    }

    private <T> List<T> scope(final TFile subject, final User user) {
        if (user.isSharing())
            return (List<T>) fsService.listShares(subject.getId(), user).stream().sorted(Comparator.comparing(Share::getName)).collect(Collectors.toList());

        if (user.isSearching())
            return (List<T>) fsService.search(user).stream().sorted(sorter).collect(Collectors.toList());

        if (user.isGearing())
            return (List<T>) fsService.listTyped(subject.getId(), ContentType.LABEL, user);

        return (List<T>) fsService.list(subject.getId(), user);
    }

    private <T> T byIdx(final int idx, final TFile subject, final User user) {
        return (T) scope(subject, user).get(idx);
    }
}
