package app.aaps.database.persistence.converters

import app.aaps.core.data.model.TSU
import app.aaps.database.entities.Tsunami

fun Tsunami.fromDb(): TSU =
    TSU(
        id = this.id,
        version = this.version,
        dateCreated = this.dateCreated,
        isValid = this.isValid,
        referenceId = this.referenceId,
        ids = this.interfaceIDs_backing?.fromDb() ?: app.aaps.core.data.model.IDs(),
        timestamp = this.timestamp,
        utcOffset = this.utcOffset,
        duration = this.duration,
        tsunamiMode = this.tsunamiMode
    )

fun TSU.toDb(): Tsunami =
    Tsunami(
        id = this.id,
        version = this.version,
        dateCreated = this.dateCreated,
        isValid = this.isValid,
        referenceId = this.referenceId,
        interfaceIDs_backing = this.ids.toDb(),
        timestamp = this.timestamp,
        utcOffset = this.utcOffset,
        duration = this.duration,
        tsunamiMode = this.tsunamiMode
    )
