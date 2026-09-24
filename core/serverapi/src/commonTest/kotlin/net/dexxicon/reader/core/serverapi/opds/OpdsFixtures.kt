package net.dexxicon.reader.core.serverapi.opds

/** issue #291 — trimmed copies of the real Project Gutenberg (OPDS 1) and Open Library (OPDS 2)
 *  feeds, fetched 2026-09-24; inline data-URI icons shortened, a few entries each. */
internal object OpdsFixtures {
    const val GUTENBERG_ROOT = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:relevance="http://a9.com/-/opensearch/extensions/relevance/1.0/">
<id>http://www.gutenberg.org/ebooks.opds/</id>
<updated>2026-09-24T17:05:36Z</updated>
<title>Project Gutenberg</title>
<subtitle>Free eBooks since 1971.</subtitle>
<author>
<name>Project Gutenberg</name>
<uri>https://www.gutenberg.org</uri>
<email>webmaster@gutenberg.org</email>
</author>
<icon>https://www.gutenberg.org/gutenberg/favicon.ico</icon>
<link rel="search" type="application/opensearchdescription+xml" title="Project Gutenberg Catalog Search" href="https://www.gutenberg.org/catalog/osd-books.xml"/>
<link rel="self" title="This Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks.opds/"/>
<link rel="alternate" type="text/html" title="HTML Page" href="/ebooks/"/>
<link rel="start" title="Start Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks.opds/"/>
<opensearch:itemsPerPage>25</opensearch:itemsPerPage>
<opensearch:startIndex>1</opensearch:startIndex>
<entry>
<updated>2026-09-24T17:05:36Z</updated>
<id>https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads</id>
<title>Popular</title>
<content type="text">Our most popular books.</content>
<link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/search.opds/?sort_order=downloads"/>
<link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
<entry>
<updated>2026-09-24T17:05:36Z</updated>
<id>https://www.gutenberg.org/ebooks/search.opds/?sort_order=release_date</id>
<title>Latest</title>
<content type="text">Our latest releases.</content>
<link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/search.opds/?sort_order=release_date"/>
<link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
<entry>
<updated>2026-09-24T17:05:36Z</updated>
<id>https://www.gutenberg.org/ebooks/search.opds/?sort_order=random</id>
<title>Random</title>
<content type="text">Random books.</content>
<link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/search.opds/?sort_order=random"/>
<link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
</feed>"""

    const val GUTENBERG_POPULAR = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:relevance="http://a9.com/-/opensearch/extensions/relevance/1.0/">
<id>http://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads</id>
<updated>2026-09-24T17:05:37Z</updated>
<title>All Books</title>
<subtitle>Free eBooks since 1971.</subtitle>
<author>
<name>Project Gutenberg</name>
<uri>https://www.gutenberg.org</uri>
<email>webmaster@gutenberg.org</email>
</author>
<icon>https://www.gutenberg.org/gutenberg/favicon.ico</icon>
<link rel="search" type="application/opensearchdescription+xml" title="Project Gutenberg Catalog Search" href="https://www.gutenberg.org/catalog/osd-books.xml"/>
<link rel="self" title="This Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?sort_order=downloads"/>
<link rel="alternate" type="text/html" title="HTML Page" href="/ebooks/search/?sort_order=downloads"/>
<link rel="start" title="Start Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks.opds/"/>
<link rel="next" title="Next Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?sort_order=downloads&amp;start_index=26"/>
<opensearch:itemsPerPage>25</opensearch:itemsPerPage>
<opensearch:startIndex>1</opensearch:startIndex>
<entry>
<updated>2026-09-24T17:05:37Z</updated>
<id>https://www.gutenberg.org/ebooks/1342.opds</id>
<title>Pride and Prejudice</title>
<content type="text">Jane Austen</content>
<link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/1342.opds"/>
<link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
<entry>
<updated>2026-09-24T17:05:37Z</updated>
<id>https://www.gutenberg.org/ebooks/2701.opds</id>
<title>Moby Dick; Or, The Whale</title>
<content type="text">Herman Melville</content>
<link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/2701.opds"/>
<link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
<entry>
<updated>2026-09-24T17:05:37Z</updated>
<id>https://www.gutenberg.org/ebooks/84.opds</id>
<title>Frankenstein; or, the modern prometheus</title>
<content type="text">Mary Wollstonecraft Shelley</content>
<link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/84.opds"/>
<link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,AAAA"/>
</entry>
</feed>"""

    const val GUTENBERG_BOOK = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:relevance="http://a9.com/-/opensearch/extensions/relevance/1.0/">
<id>http://www.gutenberg.org/ebooks/1342.opds</id>
<updated>2026-09-24T17:05:37Z</updated>
<title>Pride and Prejudice by Jane Austen</title>
<subtitle>Free eBooks since 1971.</subtitle>
<author>
<name>Project Gutenberg</name>
<uri>https://www.gutenberg.org</uri>
<email>webmaster@gutenberg.org</email>
</author>
<icon>https://www.gutenberg.org/gutenberg/favicon.ico</icon>
<link rel="search" type="application/opensearchdescription+xml" title="Project Gutenberg Catalog Search" href="https://www.gutenberg.org/catalog/osd-books.xml"/>
<link rel="self" title="This Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks/1342.opds"/>
<link rel="alternate" type="text/html" title="HTML Page" href="/ebooks/1342"/>
<link rel="start" title="Start Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks.opds/"/>
<opensearch:itemsPerPage>25</opensearch:itemsPerPage>
<opensearch:startIndex>1</opensearch:startIndex>
<entry>
<updated>2026-09-24T17:05:37Z</updated>
<title>Pride and Prejudice</title>
<content type="text">Pride and Prejudice by Jane Austen.</content>
<id>urn:gutenberg:1342:2</id>
<published>1998-06-01T00:00:00+00:00</published>
<rights>Public domain in the USA.</rights>
<author>
<name>Austen, Jane</name>
</author>
<category scheme="http://purl.org/dc/terms/LCSH" term="England -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Young women -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Love stories"/><category scheme="http://purl.org/dc/terms/LCSH" term="Sisters -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Domestic fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Courtship -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Social classes -- Fiction"/>
<category scheme="http://purl.org/dc/terms/LCC" term="PR" label="Language and Literatures: English literature"/>
<category scheme="http://purl.org/dc/terms/DCMIType" term="Text"/>
<dcterms:language>en</dcterms:language>
<relevance:score>1</relevance:score>
<link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (older e-readers, no images)" length="558381" href="https://www.gutenberg.org/ebooks/1342.epub.noimages"/>
<link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" title="Kindle (no images)" length="540013" href="https://www.gutenberg.org/ebooks/1342.kindle.noimages"/>
<link type="image/jpeg" rel="http://opds-spec.org/image" href="https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg"/>
<link type="image/jpeg" rel="http://opds-spec.org/image/thumbnail" href="https://www.gutenberg.org/cache/epub/1342/pg1342.cover.small.jpg"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/author/68.opds" title="By Austen, Jane…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/1702.opds" title="On England -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2481.opds" title="On Young women -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2487.opds" title="On Love stories…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2489.opds" title="On Sisters -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2514.opds" title="On Domestic fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2578.opds" title="On Courtship -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2906.opds" title="On Social classes -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/13.opds" title="In Best Books Ever Listings…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/40.opds" title="In Harvard Classics…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/639.opds" title="In Category: Romance…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/645.opds" title="In Category: Novels…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/649.opds" title="In Category: Classics of Literature…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/653.opds" title="In Category: British Literature…"/>
</entry>
<entry>
<updated>2026-09-24T17:05:37Z</updated>
<title>Pride and Prejudice</title>
<content type="text">Pride and Prejudice by Jane Austen.</content>
<id>urn:gutenberg:1342:3</id>
<published>1998-06-01T00:00:00+00:00</published>
<rights>Public domain in the USA.</rights>
<author>
<name>Austen, Jane</name>
</author>
<category scheme="http://purl.org/dc/terms/LCSH" term="England -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Young women -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Love stories"/><category scheme="http://purl.org/dc/terms/LCSH" term="Sisters -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Domestic fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Courtship -- Fiction"/><category scheme="http://purl.org/dc/terms/LCSH" term="Social classes -- Fiction"/>
<category scheme="http://purl.org/dc/terms/LCC" term="PR" label="Language and Literatures: English literature"/>
<category scheme="http://purl.org/dc/terms/DCMIType" term="Text"/>
<dcterms:language>en</dcterms:language>
<relevance:score>1</relevance:score>
<link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB3 (E-readers incl. Send-to-Kindle)" length="24835578" href="https://www.gutenberg.org/ebooks/1342.epub3.images"/>
<link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (older e-readers)" length="24846132" href="https://www.gutenberg.org/ebooks/1342.epub.images"/>
<link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" title="Kindles (kf8)" length="25335116" href="https://www.gutenberg.org/ebooks/1342.kf8.images"/>
<link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" title="Older Kindles" length="25224268" href="https://www.gutenberg.org/ebooks/1342.kindle.images"/>
<link type="image/jpeg" rel="http://opds-spec.org/image" href="https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg"/>
<link type="image/jpeg" rel="http://opds-spec.org/image/thumbnail" href="https://www.gutenberg.org/cache/epub/1342/pg1342.cover.small.jpg"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/author/68.opds" title="By Austen, Jane…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/1702.opds" title="On England -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2481.opds" title="On Young women -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2487.opds" title="On Love stories…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2489.opds" title="On Sisters -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2514.opds" title="On Domestic fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2578.opds" title="On Courtship -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/subject/2906.opds" title="On Social classes -- Fiction…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/13.opds" title="In Best Books Ever Listings…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/40.opds" title="In Harvard Classics…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/639.opds" title="In Category: Romance…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/645.opds" title="In Category: Novels…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/649.opds" title="In Category: Classics of Literature…"/>
<link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/653.opds" title="In Category: British Literature…"/>
</entry>
</feed>"""

    const val GUTENBERG_OSD = """<?xml version="1.0" encoding="UTF-8"?>

<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
   <LongName>Project Gutenberg</LongName>
   <ShortName>Gutenberg</ShortName>
   <Description>Search the Project Gutenberg ebook catalog.</Description>
   <Tags>free ebooks books public domain</Tags>
   <Developer>Marcello Perathoner</Developer>
   <Contact>webmaster@gutenberg.org</Contact>

   <Url type="text/html" 
        template="http://www.gutenberg.org/ebooks/search/?query={searchTerms}"/>

   <Url type="application/atom+xml"
        template="http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}"/>

   <Url type="application/x-suggestions+json"
	rel="suggestions"
        template="http://www.gutenberg.org/ebooks/suggest/?query={searchTerms}"/>
<!--
   <Url type="application/rss+xml"
        template="http://example.com/?q={searchTerms}&amp;pw={startPage?}&amp;format=rss"/>
   <Image height="64" width="64" type="image/png">http://example.com/websearch.png</Image>
   <Image height="16" width="16" type="image/vnd.microsoft.icon">http://example.com/websearch.ico</Image>
-->

   <Query role="example" searchTerms="shakespeare hamlet" />
   <Query role="example" searchTerms="doyle detective" />
   <Query role="example" searchTerms="love stories" />

   <Attribution>Search Data Copyright 1971-2012, Project Gutenberg, All Rights Reserved.</Attribution>
   <SyndicationRight>open</SyndicationRight>
   <Language>en-us</Language>
   <OutputEncoding>UTF-8</OutputEncoding>
   <InputEncoding>UTF-8</InputEncoding>
</OpenSearchDescription>
"""

    const val OPEN_LIBRARY_ROOT = """{
 "@context": "https://readium.org/webpub-manifest/context.jsonld",
 "metadata": {
  "title": "Open Library"
 },
 "links": [
  {
   "href": "https://openlibrary.org/opds/",
   "type": "application/opds+json",
   "rel": "self"
  },
  {
   "href": "https://openlibrary.org/opds/",
   "type": "application/opds+json",
   "rel": "start"
  },
  {
   "href": "https://openlibrary.org/opds/search{?query}",
   "type": "application/opds+json",
   "rel": "search",
   "templated": true
  },
  {
   "href": "https://archive.org/services/loans/loan/?action=user_bookshelf",
   "type": "application/opds+json",
   "rel": "http://opds-spec.org/shelf"
  },
  {
   "href": "https://archive.org/services/loans/loan/?action=user_profile",
   "type": "application/opds-profile+json",
   "rel": "profile"
  },
  {
   "href": "https://openlibrary.org/opds/?page=2",
   "type": "application/opds+json",
   "rel": "next"
  }
 ],
 "navigation": [
  {
   "href": "https://openlibrary.org/opds/search?sort=trending&title=Art&query=subject_key%3Aart+-subject%3A%22content_warning%3Acover%22+ebook_access%3A%5Bborrowable+TO+%2A%5D",
   "title": "Art",
   "type": "application/opds+json"
  },
  {
   "href": "https://openlibrary.org/opds/search?sort=trending&title=Science+Fiction&query=subject_key%3Ascience_fiction+-subject%3A%22content_warning%3Acover%22+ebook_access%3A%5Bborrowable+TO+%2A%5D",
   "title": "Science Fiction",
   "type": "application/opds+json"
  }
 ],
 "groups": [
  {
   "metadata": {
    "title": "Trending Books",
    "numberOfItems": 25379,
    "itemsPerPage": 25,
    "currentPage": 1
   },
   "links": [
    {
     "href": "https://openlibrary.org/opds/search?query=trending_score_hourly_sum%3A%5B1+TO+%2A%5D+-subject%3A%22content_warning%3Acover%22+ebook_access%3A%5Bborrowable+TO+%2A%5D+readinglog_count%3A%5B4+TO+%2A%5D&limit=25&sort=trending&title=Trending+Books",
     "type": "application/opds+json",
     "rel": "self"
    },
    {
     "href": "https://openlibrary.org/opds/search?query=trending_score_hourly_sum%3A%5B1+TO+%2A%5D+-subject%3A%22content_warning%3Acover%22+ebook_access%3A%5Bborrowable+TO+%2A%5D+readinglog_count%3A%5B4+TO+%2A%5D&limit=25&sort=trending&title=Trending+Books&page=1",
     "type": "application/opds+json",
     "rel": "first"
    },
    {
     "href": "https://openlibrary.org/opds/search?query=trending_score_hourly_sum%3A%5B1+TO+%2A%5D+-subject%3A%22content_warning%3Acover%22+ebook_access%3A%5Bborrowable+TO+%2A%5D+readinglog_count%3A%5B4+TO+%2A%5D&limit=25&sort=trending&title=Trending+Books&page=2",
     "type": "application/opds+json",
     "rel": "next"
    },
    {
     "href": "https://openlibrary.org/opds/search?query=trending_score_hourly_sum%3A%5B1+TO+%2A%5D+-subject%3A%22content_warning%3Acover%22+ebook_access%3A%5Bborrowable+TO+%2A%5D+readinglog_count%3A%5B4+TO+%2A%5D&limit=25&sort=trending&title=Trending+Books&page=1016",
     "type": "application/opds+json",
     "rel": "last"
    }
   ],
   "publications": [
    {
     "metadata": {
      "title": "Pocket ref",
      "@type": "http://schema.org/Book",
      "language": [
       "en"
      ],
      "author": [
       {
        "name": "Thomas J. Glover",
        "links": [
         {
          "href": "https://openlibrary.org/authors/OL255477A",
          "type": "text/html",
          "rel": "author"
         },
         {
          "href": "https://openlibrary.org/opds/authors/OL255477A",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "subject": [
       {
        "name": "Tables",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Tables%22&title=Tables",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Technology",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Technology%22&title=Technology",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science%22&title=Science",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "numberOfPages": 656,
      "aggregateRating": {
       "@type": "AggregateRating",
       "ratingValue": 4.0,
       "ratingCount": 1,
       "bestRating": 5,
       "worstRating": 1
      }
     },
     "links": [
      {
       "href": "https://openlibrary.org/opds/books/OL1125732M",
       "type": "application/opds-publication+json",
       "rel": "self"
      },
      {
       "href": "https://openlibrary.org/books/OL1125732M",
       "type": "text/html",
       "rel": "alternate"
      },
      {
       "href": "https://openlibrary.org/books/OL1125732M.json",
       "type": "application/json",
       "rel": "alternate"
      },
      {
       "href": "https://archive.org/services/loans/loan/?action=webpub&identifier=pocketref0000glov&opds=1",
       "type": "application/opds-publication+json",
       "rel": "alternate",
       "title": "Internet Archive",
       "properties": {
        "authenticate": {
         "href": "https://archive.org/services/loans/loan/?action=authentication_document",
         "type": "application/opds-authentication+json"
        }
       }
      }
     ],
     "images": [
      {
       "href": "https://covers.openlibrary.org/b/id/931848-L.jpg",
       "type": "image/jpeg",
       "rel": "cover"
      }
     ]
    },
    {
     "metadata": {
      "title": "The Invisible Man",
      "@type": "http://schema.org/Book",
      "language": [
       "en"
      ],
      "description": "Griffin, a scientist, has devoted his life to the study of optics. As his work progresses, he invents a method of making a person invisible. After testing the experiment on himself, he comes to realize that while the experiment was a complete success, he has no way of reversing his invisibility.\n\t\t\tWritten in a time of rapid scientific progress and industrial development, Wells uses Griffin’s struggle with his condition and descent into obsession and madness to reflect on the dangers of unbridled scientific progress untempered by compassion or humanity.\n\t\t\tThe Invisible Man was initially serialized in Pearson’s Weekly in 1897, after which it was published as a whole novel that same year.",
      "author": [
       {
        "name": "H. G. Wells",
        "links": [
         {
          "href": "https://openlibrary.org/authors/OL13066A",
          "type": "text/html",
          "rel": "author"
         },
         {
          "href": "https://openlibrary.org/opds/authors/OL13066A",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "subject": [
       {
        "name": "Ciencia-ficción",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Ciencia-ficci%C3%B3n%22&title=Ciencia-ficci%C3%B3n",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Classic Literature",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Classic+Literature%22&title=Classic+Literature",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Fiction%22&title=Fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Mentally ill",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Mentally+ill%22&title=Mentally+ill",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science Fiction & Fantasy",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science+Fiction+%26+Fantasy%22&title=Science+Fiction+%26+Fantasy",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science+fiction%22&title=Science+fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Scientists",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Scientists%22&title=Scientists",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "English Science fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22English+Science+fiction%22&title=English+Science+fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Experiments",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Experiments%22&title=Experiments",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Adaptations",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Adaptations%22&title=Adaptations",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "numberOfPages": 170,
      "aggregateRating": {
       "@type": "AggregateRating",
       "ratingValue": 3.8,
       "ratingCount": 108,
       "bestRating": 5,
       "worstRating": 1
      }
     },
     "links": [
      {
       "href": "https://openlibrary.org/opds/books/OL37044712M",
       "type": "application/opds-publication+json",
       "rel": "self"
      },
      {
       "href": "https://openlibrary.org/books/OL37044712M",
       "type": "text/html",
       "rel": "alternate"
      },
      {
       "href": "https://openlibrary.org/books/OL37044712M.json",
       "type": "application/json",
       "rel": "alternate"
      },
      {
       "href": "https://standardebooks.org/ebooks/h-g-wells/the-invisible-man/downloads/h-g-wells_the-invisible-man.epub",
       "type": "application/epub+zip",
       "rel": "http://opds-spec.org/acquisition/open-access",
       "title": "standard_ebooks",
       "properties": {
        "availability": "available",
        "indirectAcquisition": [
         {
          "type": "application/epub+zip",
          "title": "standard_ebooks"
         }
        ]
       }
      }
     ],
     "images": [
      {
       "href": "https://covers.openlibrary.org/b/id/12622010-L.jpg",
       "type": "image/jpeg",
       "rel": "cover",
       "width": 333,
       "height": 500
      }
     ]
    }
   ]
  },
  {
   "metadata": {
    "title": "Classic Books",
    "description": "Beloved works from before 1950 that have been digitized and made available to the public as ebooks.",
    "numberOfItems": 4704,
    "itemsPerPage": 25,
    "currentPage": 1
   },
   "links": [
    {
     "href": "https://openlibrary.org/opds/search?query=ddc%3A8%2A+first_publish_year%3A%5B%2A+TO+1950%5D+publish_year%3A%5B2000+TO+%2A%5D+NOT+public_scan_b%3Afalse+-subject%3A%22content_warning%3Acover%22&limit=25&sort=trending&title=Classic+Books",
     "type": "application/opds+json",
     "rel": "self"
    },
    {
     "href": "https://openlibrary.org/opds/search?query=ddc%3A8%2A+first_publish_year%3A%5B%2A+TO+1950%5D+publish_year%3A%5B2000+TO+%2A%5D+NOT+public_scan_b%3Afalse+-subject%3A%22content_warning%3Acover%22&limit=25&sort=trending&title=Classic+Books&page=1",
     "type": "application/opds+json",
     "rel": "first"
    },
    {
     "href": "https://openlibrary.org/opds/search?query=ddc%3A8%2A+first_publish_year%3A%5B%2A+TO+1950%5D+publish_year%3A%5B2000+TO+%2A%5D+NOT+public_scan_b%3Afalse+-subject%3A%22content_warning%3Acover%22&limit=25&sort=trending&title=Classic+Books&page=2",
     "type": "application/opds+json",
     "rel": "next"
    },
    {
     "href": "https://openlibrary.org/opds/search?query=ddc%3A8%2A+first_publish_year%3A%5B%2A+TO+1950%5D+publish_year%3A%5B2000+TO+%2A%5D+NOT+public_scan_b%3Afalse+-subject%3A%22content_warning%3Acover%22&limit=25&sort=trending&title=Classic+Books&page=189",
     "type": "application/opds+json",
     "rel": "last"
    }
   ],
   "publications": [
    {
     "metadata": {
      "title": "The Invisible Man",
      "@type": "http://schema.org/Book",
      "language": [
       "en"
      ],
      "description": "Griffin, a scientist, has devoted his life to the study of optics. As his work progresses, he invents a method of making a person invisible. After testing the experiment on himself, he comes to realize that while the experiment was a complete success, he has no way of reversing his invisibility.\n\t\t\tWritten in a time of rapid scientific progress and industrial development, Wells uses Griffin’s struggle with his condition and descent into obsession and madness to reflect on the dangers of unbridled scientific progress untempered by compassion or humanity.\n\t\t\tThe Invisible Man was initially serialized in Pearson’s Weekly in 1897, after which it was published as a whole novel that same year.",
      "author": [
       {
        "name": "H. G. Wells",
        "links": [
         {
          "href": "https://openlibrary.org/authors/OL13066A",
          "type": "text/html",
          "rel": "author"
         },
         {
          "href": "https://openlibrary.org/opds/authors/OL13066A",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "subject": [
       {
        "name": "Ciencia-ficción",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Ciencia-ficci%C3%B3n%22&title=Ciencia-ficci%C3%B3n",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Classic Literature",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Classic+Literature%22&title=Classic+Literature",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Fiction%22&title=Fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Mentally ill",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Mentally+ill%22&title=Mentally+ill",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science Fiction & Fantasy",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science+Fiction+%26+Fantasy%22&title=Science+Fiction+%26+Fantasy",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science+fiction%22&title=Science+fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Scientists",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Scientists%22&title=Scientists",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "English Science fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22English+Science+fiction%22&title=English+Science+fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Experiments",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Experiments%22&title=Experiments",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Adaptations",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Adaptations%22&title=Adaptations",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "numberOfPages": 170,
      "aggregateRating": {
       "@type": "AggregateRating",
       "ratingValue": 3.8,
       "ratingCount": 108,
       "bestRating": 5,
       "worstRating": 1
      }
     },
     "links": [
      {
       "href": "https://openlibrary.org/opds/books/OL37044712M",
       "type": "application/opds-publication+json",
       "rel": "self"
      },
      {
       "href": "https://openlibrary.org/books/OL37044712M",
       "type": "text/html",
       "rel": "alternate"
      },
      {
       "href": "https://openlibrary.org/books/OL37044712M.json",
       "type": "application/json",
       "rel": "alternate"
      },
      {
       "href": "https://standardebooks.org/ebooks/h-g-wells/the-invisible-man/downloads/h-g-wells_the-invisible-man.epub",
       "type": "application/epub+zip",
       "rel": "http://opds-spec.org/acquisition/open-access",
       "title": "standard_ebooks",
       "properties": {
        "availability": "available",
        "indirectAcquisition": [
         {
          "type": "application/epub+zip",
          "title": "standard_ebooks"
         }
        ]
       }
      }
     ],
     "images": [
      {
       "href": "https://covers.openlibrary.org/b/id/12622010-L.jpg",
       "type": "image/jpeg",
       "rel": "cover",
       "width": 333,
       "height": 500
      }
     ]
    },
    {
     "metadata": {
      "title": "The Invisible Man",
      "@type": "http://schema.org/Book",
      "language": [
       "en"
      ],
      "description": "Griffin, a scientist, has devoted his life to the study of optics. As his work progresses, he invents a method of making a person invisible. After testing the experiment on himself, he comes to realize that while the experiment was a complete success, he has no way of reversing his invisibility.\n\t\t\tWritten in a time of rapid scientific progress and industrial development, Wells uses Griffin’s struggle with his condition and descent into obsession and madness to reflect on the dangers of unbridled scientific progress untempered by compassion or humanity.\n\t\t\tThe Invisible Man was initially serialized in Pearson’s Weekly in 1897, after which it was published as a whole novel that same year.",
      "author": [
       {
        "name": "H. G. Wells",
        "links": [
         {
          "href": "https://openlibrary.org/authors/OL13066A",
          "type": "text/html",
          "rel": "author"
         },
         {
          "href": "https://openlibrary.org/opds/authors/OL13066A",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "subject": [
       {
        "name": "Ciencia-ficción",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Ciencia-ficci%C3%B3n%22&title=Ciencia-ficci%C3%B3n",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Classic Literature",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Classic+Literature%22&title=Classic+Literature",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Fiction%22&title=Fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Mentally ill",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Mentally+ill%22&title=Mentally+ill",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science Fiction & Fantasy",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science+Fiction+%26+Fantasy%22&title=Science+Fiction+%26+Fantasy",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Science fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Science+fiction%22&title=Science+fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Scientists",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Scientists%22&title=Scientists",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "English Science fiction",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22English+Science+fiction%22&title=English+Science+fiction",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Experiments",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Experiments%22&title=Experiments",
          "type": "application/opds+json"
         }
        ]
       },
       {
        "name": "Adaptations",
        "links": [
         {
          "href": "https://openlibrary.org/opds/search?query=subject%3A%22Adaptations%22&title=Adaptations",
          "type": "application/opds+json"
         }
        ]
       }
      ],
      "numberOfPages": 170,
      "aggregateRating": {
       "@type": "AggregateRating",
       "ratingValue": 3.8,
       "ratingCount": 108,
       "bestRating": 5,
       "worstRating": 1
      }
     },
     "links": [
      {
       "href": "https://openlibrary.org/opds/books/OL37044712M",
       "type": "application/opds-publication+json",
       "rel": "self"
      },
      {
       "href": "https://openlibrary.org/books/OL37044712M",
       "type": "text/html",
       "rel": "alternate"
      },
      {
       "href": "https://openlibrary.org/books/OL37044712M.json",
       "type": "application/json",
       "rel": "alternate"
      },
      {
       "href": "https://standardebooks.org/ebooks/h-g-wells/the-invisible-man/downloads/h-g-wells_the-invisible-man.epub",
       "type": "application/epub+zip",
       "rel": "http://opds-spec.org/acquisition/open-access",
       "title": "standard_ebooks",
       "properties": {
        "availability": "available",
        "indirectAcquisition": [
         {
          "type": "application/epub+zip",
          "title": "standard_ebooks"
         }
        ]
       }
      }
     ],
     "images": [
      {
       "href": "https://covers.openlibrary.org/b/id/12622010-L.jpg",
       "type": "image/jpeg",
       "rel": "cover",
       "width": 333,
       "height": 500
      }
     ]
    }
   ]
  }
 ]
}"""

}
