package com.commafeed.frontend.model.request;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.io.Serializable;

@ApiModel(description = "Translate request")
@Data
public class TranslateRequest implements Serializable {
    @ApiModelProperty(value = "entry id, category id 'all' or 'starred'", required = true)
    @NotEmpty
    @Size(max = 128)
    private String id;
}
