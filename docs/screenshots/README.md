# Скриншоты интерфейса

Снимки получены из настоящих компонентов WEX с демонстрационными файлами.
Личные папки, переписки и содержимое пользовательских документов не используются.

Чтобы обновить изображения, установите Poppler и выполните из корня проекта:

```bash
xvfb-run -a ./gradlew :shared:desktopTest --tests '*ReadmeScreenshotsTest'
cp shared/build/reports/readme/*.png docs/screenshots/
```

На рабочем столе с графическим сеансом `xvfb-run -a` можно опустить.

- `file-manager.png` — менеджер файлов.
- `smali-editor.png` — редактор Smali.
- `pdf-reader.png` — PDF-книга с миниатюрами страниц.
- `class-family.png` — выбор всего класса или отдельной части.

`apk-tools.png` снят с окна анализа собственного APK WEX в отдельном демонстрационном профиле.
