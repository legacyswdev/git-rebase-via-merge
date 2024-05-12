import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;

public class GitRebaseViaMerge {

	public static void main(String...a) throws Exception {
		if(a.length==0||!new File(a[0],".git").exists()) {
			throw new IllegalArgumentException("need valid git repo path as argument");
		}
		String default_base_branch="origin/develop";
		String base_branch = default_base_branch;
		if(a.length>1) {
			base_branch = a[1];
		}
		try(Git git = Git.open(new File(a[0]))){
			String current_branch = init(git);
			String base_branch_hash=get_hash(git, base_branch);
			String current_branch_hash=get_hash(git, current_branch);
			
			if(base_branch_hash==null) {
				throw new IllegalStateException("Can't rebase. Base branch "+base_branch+" not found.");
			}
			echo("Current branch:");
			echo(current_branch+" ("+current_branch_hash+")");
			  show_commit(git,current_branch_hash);
			  echo();

			  echo("Base branch:");
			  echo(base_branch+" ("+base_branch_hash+")");
			  show_commit(git,base_branch_hash);
			  echo();
			
			  //TODO the rest
			  
			  /*
			git.checkout().setName("develop").call();
			ObjectId upstreamBranch = git.getRepository().resolve("main");
			RebaseCommand rebaseCommand = git.rebase();
			rebaseCommand.setUpstream(upstreamBranch);

			try {
			  RebaseResult result = rebaseCommand.call();
			  if (result.getStatus() == RebaseResult.Status.OK) {
			      echo("Rebase successful!");
			  } else {
			      echo("Rebase failed. TODO.");
			  }
			} catch (GitAPIException e) {
			  e.printStackTrace();
			}	
			*/	
		}

	}
	
	private static void show_commit(Git git, String current_branch_hash) {
		// TODO Auto-generated method stub
		
	}

	private static void echo(String message) {
		System.out.println(message);
	}
	private static void echo() {
		System.out.println();
	}
	
	private static String get_hash(Git git,String revision)  {
		try {
			ObjectId shortenedId = git.getRepository().resolve(revision);
			  String shortenedRevision = shortenedId.abbreviate(4).name();
			  return shortenedRevision;
		} catch (Exception e) {
			System.err.println("get_hash error for revision "+revision+" "+e.getMessage()+", returning null");
			return null;
		}
		}
	
	private static String init(Git git) throws Exception {
		Ref headRef = git.getRepository().exactRef("HEAD");
        if (headRef.isSymbolic()) {
            String targetRefName = headRef.getTarget().getName();
            String shortName = targetRefName.substring(targetRefName.indexOf("/") + 1);
            return shortName;
        } else {
            throw new IllegalStateException("Detached HEAD (no branch selected)");
        }
	}
}
