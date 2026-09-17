# Web access — setting it up

This turns your phone's tasks into a private web page you can open on a laptop.

You need two free accounts: **Supabase** (holds the data) and **Vercel** (serves
the page). Neither costs anything at this size, and no card is required.

Nothing syncs until you finish this. Until then the app behaves exactly as it
does today, with everything on the device.

**Time:** about 15 minutes, all of it in a browser.

---

## What gets sent, and what does not

**Sent:** task titles, dates, priority, notes, tags, the contact or app a task
came from, and the short evidence quote that justified it.

**Not sent:** the full text of your messages, and call transcripts. There is no
column for them in the database, so this cannot start happening by accident.

---

## 1. Create the Supabase project

1. Go to **supabase.com**, sign up, and create a **New project**.
2. Name it anything. Set a database password and save it somewhere — you will
   not need it again for this, but losing it is annoying later.
3. Pick the region closest to you (**Mumbai / ap-south-1** for India).
4. Wait for it to finish setting up — a couple of minutes.

## 2. Create the schema

1. In the project, open **SQL Editor** in the left sidebar.
2. Open `supabase/schema.sql` from this repository, copy the whole file.
3. Paste it into the editor and press **Run**.

You should see "Success. No rows returned". If you get an error, fix it and run
the whole file again — every statement in it is safe to run twice.

## 3. Create your login

1. Open **Authentication → Users** in the sidebar.
2. **Add user → Create new user**.
3. Enter an email and a password. Tick **Auto Confirm User** so you do not have
   to click a confirmation email.

This is the one account. Both the phone and the browser sign in as it.

## 4. Copy the two values you need

Open **Project Settings → API** (or **Data API**) and copy:

- **Project URL** — looks like `https://abcdefghijkl.supabase.co`
- **anon** / **publishable** key — a long string starting `eyJ...`

> The anon key is *meant* to be public; it ships inside every Supabase web
> app's JavaScript. Row-level security is what actually protects your rows, and
> the schema turns it on for both tables.
>
> Do **not** use the `service_role` key anywhere. It bypasses row-level
> security completely.

## 5. Put the web page online

1. Push this repository to GitHub if it is not already there.
2. Go to **vercel.com**, sign in with GitHub, and **Add New → Project**.
3. Import this repository.
4. Set **Root Directory** to `web`. This matters — the repository root is the
   Android app, and Vercel will fail to build if you point it there.
5. Under **Environment Variables**, add both:

   | Name | Value |
   |---|---|
   | `NEXT_PUBLIC_SUPABASE_URL` | the Project URL from step 4 |
   | `NEXT_PUBLIC_SUPABASE_ANON_KEY` | the anon key from step 4 |

6. **Deploy.**

When it finishes you get a URL like `taskmind-xyz.vercel.app`. Open it — you
should see a sign-in box. Sign in with the account from step 3. It will be
empty, which is correct; the phone has not sent anything yet.

## 6. Pair the phone

1. Open TaskMind → **Settings → Web access**.
2. Paste the **Project URL** and the **anon key**.
3. Enter the email and password from step 3.
4. Tap **Connect**.

It signs in and immediately pushes everything, then tells you how many tasks it
sent. Refresh the web page and they should be there.

---

## After that

The phone pushes when you leave the app, and hourly as a safety net. The web
page re-reads itself every minute while it is open.

**Settings → Web access** on the phone shows when it last sent something and
what happened. Three controls there:

- **Keep the web page up to date** — the master switch. Off means nothing more
  is sent; what is already on the server stays there.
- **Sync now** — pushes immediately and reports the result on screen.
- **Re-send all** — forgets what it has already sent and pushes everything
  again. Use this if the web page is missing something and you cannot see why.
- **Disconnect** — forgets the account on this phone and stops sending.

## If something goes wrong

**"No such table — has schema.sql been run?"**
Step 2 did not complete. Run the whole file again in the SQL editor.

**"Not signed in, or the key is wrong."**
The anon key is wrong or truncated — it is long, so check you copied all of it.
If the key is right, check the user from step 3 actually exists under
Authentication → Users.

**The web page is empty but the phone says it sent tasks.**
Check the browser is signed in as the *same* account the phone is paired to.
Row-level security means another account sees nothing at all rather than an
error.

**The Vercel build fails.**
Nearly always the Root Directory is not set to `web`, or one of the two
environment variables is missing. The build log names the missing variable.

---

## Deleting everything

In Supabase, **SQL Editor**, run:

```sql
drop table if exists public.tasks;
drop table if exists public.review_items;
```

Then tap **Disconnect** in the app. Your phone keeps every task; only the
mirror is gone.
