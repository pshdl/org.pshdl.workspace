/*******************************************************************************
 * PSHDL is a library and (trans-)compiler for PSHDL input. It generates
 *     output suitable for implementation or simulation of it.
 *
 *     Copyright (C) 2013 Karsten Becker (feedback (at) pshdl (dot) org)
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *     This License does not grant permission to use the trade names, trademarks,
 *     service marks, or product names of the Licensor, except as required for
 *     reasonable and customary use in describing the origin of the Work.
 *
 * Contributors:
 *     Karsten Becker - initial API and implementation
 ******************************************************************************/
package org.pshdl.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.pshdl.rest.models.CheckType;
import org.pshdl.rest.models.FileInfo;
import org.pshdl.rest.models.FileType;
import org.pshdl.rest.models.RepoInfo;
import org.pshdl.rest.models.utils.RestConstants;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.base.Charsets;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

public class RepoCache {
    private static final String JSON_VERSION = "1.0";
    private static final ObjectWriter jsonWriter = JSONHelper.getWriter();
    private static final ObjectReader jsonReader = JSONHelper.getReader(RepoInfo.class);
    private static final Logger log = Logger.getLogger(RepoCache.class.getName());

    private static class JsonFileLoader extends CacheLoader<String, RepoInfo> {

        @Override
        public RepoInfo load(String key) throws Exception {
            return loadRepoFromFile(WorkspaceHelper.getWorkspacePath(key));
        }
    }

    private static class JsonWriter implements RemovalListener<String, RepoInfo> {

        @Override
        public void onRemoval(RemovalNotification<String, RepoInfo> notification) {
            saveToFile(notification.getValue());
        }

    }

    private static LoadingCache<String, RepoInfo> repoCache = CacheBuilder.newBuilder().removalListener(new JsonWriter()).maximumSize(100).build(new JsonFileLoader());

    public static RepoInfo loadRepo(String wd) {
        try {
            return repoCache.get(wd);
        } catch (final ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static RepoInfo loadRepoFromFile(File wd) {
        final String wid = wd.getName();
        if (!wd.exists()) {
            throw new IllegalArgumentException("No such workspace:" + wid);
        }
        final File jsonFile = new File(wd, "RepoInfo.json");
        if (!jsonFile.exists()) {
            throw new IllegalArgumentException("No such workspace RepoInfo:" + wid);
        }
        try {
            RepoInfo repo = jsonReader.<RepoInfo> readValue(jsonFile);
            if (!JSON_VERSION.equals(repo.getJsonVersion())) {
                repo = new RepoInfo();
                final List<FileInfo> files = Lists.newLinkedList();
                final File srcGen = new File(wd, RestConstants.OUTPUTDIR);
                deleteDir(srcGen);
                final File[] listFiles = wd.listFiles();
                for (final File file : listFiles) {
                    if (!file.isDirectory()) {
                        if (WorkspaceHelper.REPO_INFO_JSON.equals(file.getName())) {
                            continue;
                        }
                        final FileType ft = FileType.of(file.getName());
                        if (ft != FileType.unknown) {
                            final FileInfo fi = new FileInfo();
                            fi.setFromFile(file, CheckType.unknown, wid, wd);
                            files.add(fi);
                        }
                    }
                }
                repo.setInfo(wid, null, null, files.toArray(new FileInfo[files.size()]));
                repo.setJsonVersion(JSON_VERSION);
                log.log(Level.INFO, "Upgraded RepoInfo of workspace:" + wid);
                saveToFile(repo);
            }
            final File ownerFile = getOwnerFile(wid);
            if (ownerFile.exists()) {
                final List<String> lines = Files.readLines(ownerFile, Charsets.UTF_8);
                repo.setName(lines.get(0));
                repo.setEMail(lines.get(1));
            } else {
                repo.setName("John doe");
                repo.setEMail("john@invalid");
            }
            return repo;
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) {
            return;
        }
        final File[] files = dir.listFiles();
        for (final File file : files) {
            if (file.isDirectory()) {
                deleteDir(file);
            }
            if (!file.delete()) {
                log.log(Level.WARNING, "Failed to delete file:" + file);
            }
        }
        if (!dir.delete()) {
            log.log(Level.WARNING, "Failed to delete directory:" + dir);
        }
    }

    public static RepoInfo createRepo(String wid, String eMail, String name) throws IOException {
        final RepoInfo info = new RepoInfo();
        info.setEMail(eMail);
        info.setId(wid);
        info.setName(name);
        info.setJsonVersion(JSON_VERSION);
        Files.write(name + '\n' + eMail, getOwnerFile(wid), StandardCharsets.UTF_8);
        final File workspacePath = WorkspaceHelper.getWorkspacePath(wid);
        if (!workspacePath.mkdirs()) {
            throw new IllegalArgumentException("Failed to create directory:" + workspacePath);
        }
        saveToFile(info);
        return info;
    }

    public static File getOwnerFile(String wid) {
        return new File(WorkspaceHelper.REPODIR, wid);
    }

    public static void saveToFile(RepoInfo info) {
        final File wd = WorkspaceHelper.getWorkspacePath(info.getId());
        if (!wd.exists()) {
            throw new IllegalArgumentException("No such workspace:" + info.getId());
        }
        final File jsonFile = new File(wd, "RepoInfo.json");
        try {
            jsonWriter.writeValue(jsonFile, info);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static FileInfo removeFile(File workingDir, String f) {
        final RepoInfo repo = loadRepo(workingDir.getName());
        for (final Iterator<FileInfo> iterator = repo.getFiles().iterator(); iterator.hasNext();) {
            final FileInfo fi = iterator.next();
            if (fi.record.relPath.equals(f)) {
                iterator.remove();
                saveToFile(repo);
                return fi;
            }
        }
        return null;
    }

    public static void addFile(File workingDir, FileInfo newFile) {
        final RepoInfo repo = loadRepo(workingDir.getName());
        repo.getFiles().add(newFile);
        saveToFile(repo);
    }

    public static FileInfo updateFile(File workingDir, File f) throws IOException {
        final RepoInfo repo = loadRepo(workingDir.getName());
        FileInfo info = repo.getFile(f.getName());
        if (info == null) {
            info = new FileInfo();
            repo.getFiles().add(info);
        }
        info.setFromFile(f, CheckType.unknown, workingDir.getName(), workingDir);
        saveToFile(repo);
        return info;
    }

}
