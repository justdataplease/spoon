# Τι θα φάμε; (Spoon)

A Greek-language Android app that helps answer the everyday question: **what are we eating?**
Plan your week, discover recipes from Greek cooking sites, and keep your favorites,
shopping list, and cooking history together.

**[Download the latest APK](https://github.com/justdataplease/spoon/releases/latest)**
— Android 8.0 or newer. Open the downloaded APK on your phone and allow installation
from that source when Android asks.

## What you can do

- **Plan a week of meals.** Get suggestions for each day, replace a single dish or
  refresh the week, and lock meals you want to keep. Add sides and desserts for a full menu.
- **Find something that fits.** Search in Greek and filter by category, preparation
  time, effort, rating, diet, cuisine, ingredients, and more. Choose which publishers
  appear in new suggestions, or plan using only your favorites.
- **Keep your own collection.** Save favorites, add private recipe notes, and create
  personal recipes with ingredients, steps, and a photo.
- **Make shopping easier.** Add a recipe's ingredients to your shopping list in one
  tap, add your own items, and tick things off as you go.
- **Remember what you cooked.** Mark meals as cooked, revisit your history, and
  browse past or upcoming plans in the calendar.
- **Read, watch, and share.** View recipe instructions, photos, nutrition and videos
  where available, visit the original publisher, and share catalog recipe links.
- **See meals on your home screen.** Choose a large or compact today's-recipe widget,
  or an interactive month calendar with the selected day's meal. Tap a recipe to open it.
  [Widget guide](docs/today-recipe-widget.md).

## Local use or a Firebase account

You can use Spoon **locally, without an account**. The recipe catalog and your
personal data are stored on your phone, so planning, search, favorites, notes,
shopping lists, and cooking history work offline. Photos and videos may need an
internet connection.

With Firebase configured, you can optionally sign in with an email/password
account to synchronize personal data across devices. Existing local data carries
across when you sign in, and offline changes sync when connectivity returns.
**New account registration is currently disabled on the maintainer's Firebase
project; local use remains available to everyone.**

## Recipe sources

The catalog brings together recipes from seven publishers. Each recipe credits
its source and links to the original page. You can enable or disable individual
sources in Settings.

- [Άκης Πετρετζίκης](https://akispetretzikis.com/)
- [Αργυρώ Μπαρμπαρίγου](https://www.argiro.gr/)
- [Γαστρονόμος](https://www.gastronomos.gr/)
- [Γιώργος Τσούλης](https://www.giorgostsoulis.com/)
- [Cookpad Greece](https://cookpad.com/gr)
- [Γιάννης Λουκάκος](https://www.yiannislucacos.gr/)
- [Funky Cook](https://funkycook.gr/)

## Built with

Spoon is written in **Kotlin**, with **Jetpack Compose** for the Android interface.
**SQLite** keeps recipes and personal data on the device. Optional **Firebase
Authentication** and **Cloud Firestore** provide account sign-in and cloud sync;
Firebase is not required for local use. Independent builds can connect to their
own Firebase project using an untracked `app/google-services.json`.

For development details, see the [recipe catalog tooling](tools/recipe_importer/README.md)
and [Android verification guide](docs/android-device-validation.md). Maintainer
cloud protections and cost limits are described in [Security](SECURITY.md).

## License and recipe rights

The application code and project documentation are available under the **[MIT
License](LICENSE)**.

Spoon is an educational project intended for **personal use only**. Recipes,
photographs, videos, publisher names, logos, and other third-party materials remain
the intellectual property of their respective creators and rights holders. They
are **not covered by the MIT license**, and this project grants no permission to
copy, redistribute, or commercially use those materials. Please respect each
publisher's terms and permissions and visit the original sources.

This intended-use notice does not restrict the permissions granted by the MIT
license for the application's code.
