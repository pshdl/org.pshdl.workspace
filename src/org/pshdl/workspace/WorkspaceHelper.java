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
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pshdl.model.utils.PSAbstractCompiler.CompileResult;
import org.pshdl.model.utils.services.IHDLGenerator.SideFile;
import org.pshdl.model.validation.Problem;
import org.pshdl.rest.models.CheckType;
import org.pshdl.rest.models.CompileInfo;
import org.pshdl.rest.models.FileInfo;
import org.pshdl.rest.models.FileRecord;
import org.pshdl.rest.models.Message;
import org.pshdl.rest.models.ProblemInfo;
import org.pshdl.rest.models.RepoInfo;
import org.pshdl.rest.models.utils.RestConstants;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class WorkspaceHelper {
	public static final String REPO_INFO_JSON = "RepoInfo.json";
	private static Logger LOG = Logger.getLogger(WorkspaceHelper.class.getName());

	public static interface MessagingService {
		public void pushMessage(String wid, Message<?> message) throws JsonProcessingException;
	}

	public static MessagingService service = new MessagingService() {

		@Override
		public void pushMessage(String wid, Message<?> message) throws JsonProcessingException {
		}
	};

	public static void addFile(File workingDir, boolean created, File... newFiles) throws Exception {
		final List<FileInfo> added = Lists.newLinkedList();
		final List<FileInfo> updated = Lists.newLinkedList();
		for (final File newFile : newFiles) {
			if (created) {
				final FileInfo newInfo = new FileInfo();
				newInfo.setFromFile(newFile, CheckType.unknown, workingDir.getName(), workingDir);
				RepoCache.addFile(workingDir, newInfo);
				GITTools.addToGit(workingDir, newFile);
				added.add(newInfo);
			} else {
				final FileInfo newInfo = RepoCache.updateFile(workingDir, newFile);
				updated.add(newInfo);
			}
		}
		if (!GITTools.isClean(workingDir)) {
			GITTools.commitAll(workingDir, "Updated/Added file(s):" + Arrays.toString(newFiles));
			if (!added.isEmpty()) {
				service.pushMessage(workingDir.getName(), new Message<>("FileInfo[]", Message.WORK_ADDED, added.toArray(new FileInfo[added.size()]), null));
			}
			if (!updated.isEmpty()) {
				service.pushMessage(workingDir.getName(), new Message<>("FileInfo[]", Message.WORK_UPDATED, updated.toArray(new FileInfo[updated.size()]), null));
			}
		}
	}

	public static File getWorkspacePath(String wid) {
		final File file = new File(BASEDIR, wid);
		try {
			final String fc = file.getCanonicalPath();
			final String bc = BASEDIR.getCanonicalPath();
			if (!fc.startsWith(bc)) {
				LOG.log(Level.WARNING, "getWorkspacePath()" + wid);
				throw new IllegalArgumentException("The workspace ID is not valid");
			}
		} catch (final IOException e) {
			throw new IllegalArgumentException("The workspace ID is not valid");
		}
		return file;
	}

	public static final File BASEDIR = new File("/var/pshdl");
	public static final File REPODIR = new File(BASEDIR, "OwnerInfo");

	public static File getWorkspaceGenOutputPath(String wid) {
		return new File(getWorkspacePath(wid), RestConstants.OUTPUTDIR);
	}

	public static void deleteFile(File workingDir, String f) throws Exception {
		final FileInfo removed = RepoCache.removeFile(workingDir, f);
		if (removed != null) {
			final File file = new File(workingDir, f);
			if (!file.delete()) {
				LOG.log(Level.WARNING, "Failed to delete:" + file);
			}
			deleteGeneratedFiles(workingDir.getName(), removed, null);
			service.pushMessage(workingDir.getName(), new Message<>("FileInfo", Message.WORK_DELETED, removed, null));
		}
	}

	public static void createWorkspace(File workingDir, String name, String eMail) throws Exception {
		final String wid = workingDir.getName();
		name = name.trim();
		eMail = eMail.trim();
		final RepoInfo repo = RepoCache.createRepo(wid, eMail, name);
		GITTools.commitAll(workingDir, "Created Repo");
		service.pushMessage(wid, new Message<>("RepoInfo", Message.WORK_CREATED_WORKSPACE, repo, null));
	}

	public static void zip(File directory, OutputStream out, String pshdPkgFolder) throws IOException {
		final URI base = directory.toURI();
		final Deque<File> queue = Lists.newLinkedList();
		queue.push(directory);
		try (final ZipOutputStream zout = new ZipOutputStream(out)) {
			while (!queue.isEmpty()) {
				directory = queue.pop();
				final File[] listFiles = directory.listFiles();
				if (listFiles != null) {
					for (final File kid : listFiles) {
						String name = base.relativize(kid.toURI()).getPath();
						if (name.endsWith(".git/") || name.endsWith(REPO_INFO_JSON)) {
							continue;
						}
						if (kid.isDirectory()) {
							queue.push(kid);
							name = name.endsWith("/") ? name : name + "/";
							zout.putNextEntry(new ZipEntry(name));
						} else {
							zout.putNextEntry(new ZipEntry(name));
							Files.copy(kid, zout);
							zout.closeEntry();
						}
					}
				}
			}
			zout.putNextEntry(new ZipEntry(pshdPkgFolder + "pshdl_pkg.vhd"));
			ByteStreams.copy(WorkspaceHelper.class.getResourceAsStream("/pshdl_pkg.vhd"), zout);
			zout.closeEntry();
		}
	}

	public static File getWorkspaceFile(File workingDir, String fileName) {
		fileName = fileName.replace(':', '/');

		final File file = new File(workingDir, fileName);
		if (!file.getName().matches("[a-zA-Z0-9_\\.-]+"))
			throw new IllegalArgumentException("Not a valid file name, valid file name must satisfy: [a-zA-Z0-9_\\.-]+");
		try {
			final String fc = file.getCanonicalPath();
			final String wc = workingDir.getCanonicalPath();
			if (!fc.startsWith(wc)) {
				LOG.log(Level.WARNING, "getWorkspaceFile()" + workingDir + " " + fileName + " fc:" + fc + " wc:" + wc);
				throw new IllegalArgumentException("Not a valid filename");
			}
			return file;
		} catch (final IOException e) {
			throw new IllegalArgumentException("Not a valid filename");
		}
	}

	public static void deleteGeneratedFiles(String wid, final FileInfo fi, String creator) {
		final CompileInfo info = fi.info;
		if (info != null) {
			deleteFilesByCompInfo(wid, info);
		}
	}

	public static void deleteFilesByCompInfo(String wid, CompileInfo ci) {
		final File srcGenFolder = getWorkspaceGenOutputPath(wid);
		for (final FileRecord oi : ci.getFiles()) {
			final File file = new File(srcGenFolder, oi.relPath);
			deleteFile(srcGenFolder, file);
		}
	}

	private static void deleteFile(File srcGenFolder, File file) {
		if (file.exists()) {
			if (!file.delete()) {
				LOG.log(Level.WARNING, "Failed to delete file:" + file);
			}
		} else
			return;
		File folder = file.getParentFile();
		while ((folder != null) && (folder.list() != null) && !folder.equals(srcGenFolder) && (folder.list().length == 0)) {
			if (!folder.delete()) {
				LOG.log(Level.WARNING, "Failed to delete folder:" + folder);
			}
			folder = folder.getParentFile();
		}
	}

	public static String makeRelative(File vhdlFile, File workingDir) {
		final String absolutePath = workingDir.getAbsolutePath();
		final String targetPath = vhdlFile.getAbsolutePath();
		final String relPath = targetPath.substring(absolutePath.length() + 1);
		return relPath;
	}

	public static String toWorkspaceURI(File to, File workingDir) {
		final String rel = makeRelative(to, workingDir);
		return RestConstants.getWorkspaceURI(workingDir.getName()) + "/" + rel.replaceAll("\\/", ":");
	}

	private static FileRecord getRecord(final String relPath, final String wid) throws IOException {
		final File workingDir = WorkspaceHelper.getWorkspacePath(wid);
		final File to = new File(workingDir, relPath);
		return new FileRecord(to, workingDir, wid);
	}

	public static void setFromResult(CompileInfo ci, final CompileResult res, final String wid, String subDir) throws IOException {
		ci.setCreated(System.currentTimeMillis());
		final ArrayList<ProblemInfo> problems = Lists.newArrayList();
		for (final Problem p : res.syntaxProblems) {
			final ProblemInfo pi = new ProblemInfo();
			pi.setFromProblem(p);
			problems.add(pi);
		}
		ci.setProblems(problems);
		final ArrayList<FileRecord> outputs = Lists.newArrayList();
		if (!res.hasError()) {
			outputs.add(getRecord(RestConstants.OUTPUTDIR + subDir + res.fileName, wid));
			for (final SideFile sf : res.sideFiles) {
				outputs.add(getRecord(RestConstants.OUTPUTDIR + subDir + sf.relPath, wid));
			}
		}
		ci.setFiles(outputs);
	}

}
