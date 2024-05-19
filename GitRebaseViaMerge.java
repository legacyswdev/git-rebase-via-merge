import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.submodule.SubmoduleWalk.IgnoreSubmoduleMode;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.FileTreeIterator;

/**
 * This code is a work in progress and is incomplete.
 * merge then rebase -> mebase
 */
public class GitRebaseViaMerge implements AutoCloseable {

	private static final String default_base_branch = "develop";
	private final String base_branch;
	private final String current_branch;
	private final String base_branch_hash;
	private final String current_branch_hash;
	private final ObjectId baseBranchId;
	private final ObjectId currentBranchId;
	private final Git git;

	public GitRebaseViaMerge(File repo, String base_branch) throws Exception {
		this.git = Git.open(repo);
		this.base_branch = base_branch;
		var headRef = git.getRepository().findRef("HEAD");
		if (!headRef.isSymbolic()) {
			throw new IllegalStateException("Detached HEAD (no branch selected)");
		}
		var branches = git.branchList().call();
		var targetRefName = headRef.getTarget().getName();
		current_branch = targetRefName.substring(targetRefName.indexOf("/") + 1);
		var baseRef = getBranchRef(git, branches, base_branch);
		var currentRef = getBranchRef(git, branches, current_branch);

		base_branch_hash = get_hash(git, baseRef);
		current_branch_hash = get_hash(git, currentRef);

		if (base_branch_hash == null) {
			throw new IllegalStateException("Can't rebase. Base branch " + base_branch + " not found.");
		}

		if (base_branch_hash.equals(current_branch_hash)) {
			throw new IllegalStateException("Can't rebase. Current branch is equal to the base branch.");
		}

		echo("Current branch:");
		echo(current_branch + " (" + current_branch_hash + ")");
		show_commit(git, current_branch_hash);
		echo();

		echo("Base branch:");
		echo(base_branch + " (" + base_branch_hash + ")");
		show_commit(git, base_branch_hash);
		echo();

		var changedFiles = new HashSet<>();
		changedFiles.addAll(get_any_changed_files());
		changedFiles.addAll(get_unstaged_files());
		if (changedFiles.size() > 0) {
			throw new IllegalStateException(
					"Can't rebase. You need to commit changes in the following files: " + changedFiles);
		}
		baseBranchId = git.getRepository().resolve(base_branch_hash);
		currentBranchId = git.getRepository().resolve(current_branch_hash);
		if (isAlreadyRebased(git)) {
			throw new IllegalArgumentException("Can't rebase. Current branch is already rebased.");
		}
		if (!hasUniqueCommits(git)) {
			throw new IllegalArgumentException(
					"Can't rebase. Current branch has no unique commits. You can do fast-forward merge.");
		}

	}

	private Set<String> get_any_changed_files() throws Exception {
		return git.status().setIgnoreSubmodules(IgnoreSubmoduleMode.DIRTY).call().getChanged();
	}

	private Set<String> get_unstaged_files() throws Exception {
		return git.status().setIgnoreSubmodules(IgnoreSubmoduleMode.DIRTY).call().getModified();
	}

	private void show_commit(Git git, String current_branch_hash) throws Exception {
		for (RevCommit log : git.log().setMaxCount(1).call()) {
			echo(log.getShortMessage());
		}

	}

	private void echo(String message) {
		System.out.println(message);
	}

	private void echo() {
		System.out.println();
	}

	private Ref getBranchRef(Git git, List<Ref> branches, String branchShortName) {
		for (Ref branch : branches) {
			if (branch.getName().endsWith("/" + branchShortName)) {
				return branch;
			}
		}
		throw new IllegalArgumentException("cannot resolve branch " + branchShortName);
	}

	private String get_hash(Git git, Ref branchRef) {
		return branchRef.getObjectId().abbreviate(7).name();
	}

	private boolean isAlreadyRebased(Git git) throws Exception {
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			RevCommit baseCommit = walk.parseCommit(baseBranchId);
			RevCommit currentCommit = walk.parseCommit(currentBranchId);
			walk.setRevFilter(RevFilter.MERGE_BASE);
			walk.markStart(baseCommit);
			walk.markStart(currentCommit);
			RevCommit mergeBase = walk.next();

			walk.reset();
			walk.setRevFilter(RevFilter.ALL);
			RevCommit[] commits = walk.parseCommit(mergeBase).getParents();
			for (RevCommit commit : commits) {
				if (commit.equals(currentCommit)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasUniqueCommits(Git git) throws Exception {
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			walk.setRevFilter(RevFilter.ALL);
			Iterable<RevCommit> commits = git.log().addRange(baseBranchId, currentBranchId).call();
			for (RevCommit commit : commits) {
				if (!commit.equals(baseBranchId)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void close() throws Exception {
		git.close();
	}


	private boolean fix_rebase_conflicts(RebaseResult rr) throws Exception  {
		while (true) {
			echo("Fix all rebase conflicts, stage all the changes and type 'c' to continue:");
			if (isContinue()) {
				if (get_unstaged_files().size() > 0) {
					echo("There are still unstaged files. " + get_unstaged_files());
				} else {
					return true;
				}
			} else {
				echo("Aborting rebase");
				git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
				return false;
			}
		}
	}

	private RebaseResult rebase_accept_theirs() throws Exception {
		git.checkout().setName(current_branch).call();
		RebaseResult rr = git.rebase().setUpstream(baseBranchId).setStrategy(MergeStrategy.THEIRS).call();
		return rr;
	}
	

	private void merge_hidden_temp_commit(RevCommit mergeCommit) throws Exception {
		try(RevWalk revWalk = new RevWalk(git.getRepository())){
	         RevTree current_tree = revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD)).getTree();
	         RevTree result_tree = revWalk.parseCommit(git.getRepository().resolve(Constants.HEAD)).getTree();
	         if(!current_tree.getId().equals(result_tree.getId())) {
	        	 echo("Restoring project state from the hidden merge with single additional commit.");
	        	 String additional_commit_message="Rebase via merge. "+current_branch+" rebased on "+base_branch+".";
	        	 git.merge().include(mergeCommit).setFastForward(FastForwardMode.FF).setMessage(additional_commit_message).call();
	        	 echo("Done");
	         } else {
	        	 echo("You don't need additional commit. Project state is correct.");
	         }
		}
	}


	private boolean isContinue() {
		echo("Press 'c' to continue. Any other character to abort.");
		try (Scanner scanner = new Scanner(System.in)) {
			String input = scanner.nextLine();
			if ("c".equals(input)) {
				return true;
			}

		}
		return false;
	}

	private RevCommit fix_merge_conflicts(MergeResult mr) throws Exception {
		while (true) {
			echo("Fix all merge conflicts, stage all the changes and type 'c' to continue:");
			if (isContinue()) {
				if (get_unstaged_files().size() > 0) {
					echo("There are still unstaged files. " + get_unstaged_files());
				} else {
					RevCommit revC = git.commit().setMessage("Hidden orphaned commit to save merge result.").call();
					echo("Merge succeeded at hidden commit:");
					echo(revC.getId().name());
					return revC;
				}
			} else {
				echo("Aborting merge");
				git.getRepository().writeMergeCommitMsg(null);
				git.getRepository().writeMergeHeads(null);
				Git.wrap(git.getRepository()).reset().setMode(ResetType.HARD).call();
				return null;
			}
		}
	}

	public Map<String, org.eclipse.jgit.merge.MergeResult<?>> testMerge(RevCommit headCommit, RevCommit mergeCommit)
			throws Exception {
		ResolveMerger merger = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(git.getRepository(), true);
		merger.setWorkingTreeIterator(new FileTreeIterator(git.getRepository()));
		merger.setBase(headCommit.getTree());
		if (!merger.merge(headCommit, mergeCommit)) {
			if (merger.failed()) {
				throw new IllegalStateException("Should not happen with in-core mergers");
			}
		}
		return merger.getMergeResults();
	}

	private MergeResult mergeBaseIntoTempBranchOfCurrentCommitDetached() throws Exception {
		git.checkout().setName(current_branch_hash).call();
		MergeResult mr = git.merge().include(baseBranchId).setMessage("Hidden orphaned commit for merge result.")
				.call();
		var conflicts = mr.getConflicts();
		if (conflicts.size() > 0) {
			echo("You have "+conflicts.size()+"conflict in files "+conflicts.keySet());
		}
		return mr;
	}

	public static void testCloneIntoMemory(File sourceRepo) throws Exception {
		DfsRepositoryDescription repoDesc = new DfsRepositoryDescription();
		InMemoryRepository repo = new InMemoryRepository(repoDesc);
		try (Git git = new Git(repo)) {
			git.fetch().setRemote(sourceRepo.getAbsolutePath()).setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"))
					.call();
		}
	}
	

	public static void main(String... a) throws Exception {
		if (a.length == 0 || !new File(a[0], ".git").exists()) {
			throw new IllegalArgumentException("need valid git repo path as argument");
		}
		String base_branch = default_base_branch;
		if (a.length > 1) {
			base_branch = a[1];
		}
		try (GitRebaseViaMerge grvm = new GitRebaseViaMerge(new File(a[0]), base_branch)) {
			MergeResult mr = grvm.mergeBaseIntoTempBranchOfCurrentCommitDetached();
			if (mr.getConflicts().size() > 0) {
				RevCommit mergeCommit = grvm.fix_merge_conflicts(mr);
				if(mergeCommit!=null) {
					RebaseResult rr = grvm.rebase_accept_theirs();
					if (rr.getConflicts().size() > 0) {
						if(grvm.fix_rebase_conflicts(rr)) {
							grvm.merge_hidden_temp_commit(mergeCommit);
						}
					}					
				}
			}
		}
	}
}
