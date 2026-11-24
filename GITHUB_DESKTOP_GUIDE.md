# Publish RackWatch with GitHub Desktop

1. Install/open GitHub Desktop on Windows and sign in to GitHub.
2. Choose **File → Add Local Repository** if the project folder is already a Git repository.
3. If GitHub Desktop says the folder is not a repository, use **Create a New Repository** and select the exact RackWatch project folder. Use the name `RackWatch`. Do not choose an unrelated parent folder.
4. Keep the generated README option unchecked if the project already contains `README.md`.
5. Review the files before the first commit. Confirm `.env` is not included. Also exclude `node_modules/`, build outputs, generated secrets, and local database files.
6. Commit with a message such as `Initial RackWatch project`.
7. Click **Publish repository**. Choose Public only if you are comfortable making the source public and have checked for secrets and private data.

## WSL note

Your project currently lives at `/home/mohamed_yakoob/workspace` in Ubuntu. GitHub Desktop is a Windows app; Windows Git support for WSL UNC paths can vary. If Desktop cannot reliably add that path, use a normal Windows folder such as `C:\Users\<you>\source\RackWatch` as the canonical repository location. Do not maintain two independently edited copies. Copy the project once, verify it, and then use that one folder consistently.

Never commit `.env` or publish its contents.
