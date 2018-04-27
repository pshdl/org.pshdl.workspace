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
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.pshdl.rest.models.RepoInfo;

import com.google.common.collect.Maps;

public class GITTools {
    private static ConcurrentMap<String, Lock> locks = Maps.newConcurrentMap();

    public static void commitAll(File workingdir, String message) throws Exception {
        final Lock lock = lock(workingdir);
        try {
            final Git git = getOrCreateRepository(workingdir);
            final Status statusCall = git.status().call();
            final Set<String> untracked = statusCall.getUntracked();
            if (!untracked.isEmpty()) {
                final AddCommand add = git.add();
                for (final String u : untracked) {
                    add.addFilepattern(u);
                }
                add.call();
            }
            if (!statusCall.isClean()) {
                final RepoInfo repo = RepoCache.loadRepo(workingdir.getName());
                git.commit().setAll(true).setAuthor(repo.getName(), repo.getEMail()).setMessage(message).call();
                try {
                    Runtime.getRuntime().exec("git update-server-info", null, workingdir);
                } catch (final Exception e) {
                    e.printStackTrace();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public static Lock lock(File workingdir) {
        final ReentrantLock newLock = new ReentrantLock(true);
        Lock lock = locks.putIfAbsent(workingdir.getAbsolutePath(), newLock);
        if (lock == null) {
            lock = newLock;
        }
        lock.lock();
        return lock;
    }

    private static Git getOrCreateRepository(File workingDir) throws IOException {
        final File dir = new File(workingDir, ".git");
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        final Repository repository = builder.setGitDir(dir).setWorkTree(workingDir).build();
        if (!dir.exists()) {
            if (!workingDir.exists() && !workingDir.mkdirs()) {
                throw new IllegalArgumentException("Failed to create directory:" + workingDir);
            }
            repository.create();
        }
        return new Git(repository);
    }

    public static void addToGit(File workingDir, File newFile) throws Exception {
        final Lock lock = lock(workingDir);
        try {
            final File[] files = { newFile };
            AddCommand add = getOrCreateRepository(workingDir).add();
            for (final File file : files) {
                add = add.addFilepattern(WorkspaceHelper.makeRelative(file, workingDir));
            }
            add.call();
        } finally {
            lock.unlock();
        }
    }

    public static boolean isClean(File workingDir) throws Exception {
        final Lock lock = lock(workingDir);
        try {
            final Git git = getOrCreateRepository(workingDir);
            final Status status = git.status().call();
            return status.isClean();
        } finally {
            lock.unlock();
        }
    }
}
