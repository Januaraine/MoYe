# Moye Reader / 墨页阅读器

Moye is a paginated local novel reader for Android. It opens TXT and EPUB files you already have, keeps them on the device, and does not download or sell books.

墨页是一款分页式的 Android 本地小说阅读器。它打开你已经拥有的 TXT 和 EPUB，文件只留在这台设备上。

## Reading model / 阅读模型

Moye uses paginated reading.

- A page can contain multiple sentences.
- Page is the navigation unit.
- Sentence is the reveal unit.
- Sentences are revealed sequentially within a page.
- Each newly revealed sentence supports character-by-character Typewriter Effect.
- Typewriter speed is configurable.
- Typewriter Effect can be disabled.
- Disabling the effect does NOT disable sequential sentence reveal.
- EPUB embedded covers should be extracted and displayed.
- Books without covers should receive generated text-based covers.

进入新的一页时只显示第一句。点按正在打字的句子会立刻补全这一句；句子已经完整时，下一次点按才显示下一句；这一页都显示完后，下一次点按才进入下一页。上一页 / 下一页用来翻页。关闭逐字显示之后，点按仍然逐句出现，只是每一句会马上完整显示。

EPUB 里合法的内嵌封面会用在书架上。没有封面的书用书名生成文字封面，不会给已经有封面的 EPUB 再做一张文字封面。

产品范围见 [小说阅读器_MVP计划.md](小说阅读器_MVP计划.md)。构建和测试见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。许可证见 [REARMED.md](REARMED.md)。
