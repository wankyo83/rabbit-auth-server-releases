package com.rabbit.authserver.androidtest;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class ImagePageAccumulatorTest {
    @Test public void accumulatesVirtualizedWindowsInPageOrder() {
        ImagePageAccumulator accumulator = new ImagePageAccumulator();

        accumulator.merge(List.of(
            new WebAuthEngine.Page(3, List.of("https://img.example/3.gif")),
            new WebAuthEngine.Page(4, List.of("https://img.example/4.gif")),
            new WebAuthEngine.Page(5, List.of("https://img.example/5.gif"))
        ));
        accumulator.merge(List.of(
            new WebAuthEngine.Page(1, List.of("https://img.example/1.gif")),
            new WebAuthEngine.Page(2, List.of("https://img.example/2.gif")),
            new WebAuthEngine.Page(3, List.of("https://img.example/3.gif"))
        ));

        assertEquals(5, accumulator.size());
        assertEquals(List.of(1, 2, 3, 4, 5), accumulator.snapshot().stream().map(page -> page.number).toList());
    }

    @Test public void mergesCandidateUrlsWithoutDuplicatingAPage() {
        ImagePageAccumulator accumulator = new ImagePageAccumulator();

        accumulator.merge(List.of(new WebAuthEngine.Page(1, List.of("https://img.example/a.gif"))));
        accumulator.merge(List.of(new WebAuthEngine.Page(1, List.of(
            "https://img.example/a.gif",
            "https://img.example/a-backup.gif"
        ))));

        assertEquals(1, accumulator.size());
        assertEquals(
            List.of("https://img.example/a.gif", "https://img.example/a-backup.gif"),
            accumulator.snapshot().get(0).urls
        );
    }

    @Test public void buildsBlacktoonAlternateCandidateWithoutChangingThePath() {
        assertEquals(
            List.of(
                "https://images-a.example/upload/work/%5Bfolder%5D/001.jpg?token=abc",
                "https://images-b.example/upload/work/%5Bfolder%5D/001.jpg?token=abc"
            ),
            WebAuthEngine.blacktoonCandidates(
                List.of("https://images-a.example/upload/work/%5Bfolder%5D/001.jpg?token=abc"),
                "https://images-a.example/",
                "https://images-b.example/"
            )
        );
    }

    @Test public void keepsOnlyObservedUrlWhenBlacktoonDomainsAreUnavailable() {
        assertEquals(
            List.of("https://images-a.example/001.jpg"),
            WebAuthEngine.blacktoonCandidates(
                List.of("https://images-a.example/001.jpg"),
                "",
                "https://images-b.example/"
            )
        );
    }
}
