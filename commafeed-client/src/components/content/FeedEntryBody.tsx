import { Box, Button } from "@mantine/core"
import { useAppSelector } from "app/store"
import { Entry } from "app/types"
import { useAsyncCallback } from "react-async-hook"
import { client } from "app/client"
import { Content } from "./Content"
import { Enclosure } from "./Enclosure"
import { Media } from "./Media"

export interface FeedEntryBodyProps {
    entry: Entry
}

export function FeedEntryBody(props: FeedEntryBodyProps) {
    const search = useAppSelector(state => state.entries.search)
    const translate = useAsyncCallback(client.entry.tranlate, {
        onSuccess: data => {
            console.log("success: ", data)
        },
    })
    return (
        <Box>
            <Box>
                <Button variant="gradient" onClick={() => translate.execute}>
                    翻译
                </Button>
            </Box>
            <Box>
                <Content content={props.entry.content} highlight={search} />
            </Box>
            {props.entry.enclosureType && props.entry.enclosureUrl && (
                <Box pt="md">
                    <Enclosure enclosureType={props.entry.enclosureType} enclosureUrl={props.entry.enclosureUrl} />
                </Box>
            )}
            {/* show media only if we don't have content to avoid duplicate content */}
            {!props.entry.content && props.entry.mediaThumbnailUrl && (
                <Box pt="md">
                    <Media
                        thumbnailUrl={props.entry.mediaThumbnailUrl}
                        thumbnailWidth={props.entry.mediaThumbnailWidth}
                        thumbnailHeight={props.entry.mediaThumbnailHeight}
                        description={props.entry.mediaDescription}
                    />
                </Box>
            )}
        </Box>
    )
}
